/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery
package aeron

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.Done
import akka.actor.Address
import akka.actor.Cancellable
import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransportException
import akka.remote.artery.compress._
import akka.stream.KillSwitches
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ccompat._
import io.aeron.Aeron
import io.aeron.AvailableImageHandler
import io.aeron.CncFileDescriptor
import io.aeron.CommonContext
import io.aeron.Image
import io.aeron.UnavailableImageHandler
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.exceptions.ConductorServiceTimeoutException
import io.aeron.exceptions.DriverTimeoutException
import io.aeron.status.ChannelEndpointStatus
import org.agrona.DirectBuffer
import org.agrona.ErrorHandler
import org.agrona.IoUtil
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.status.CountersReader.MetaData

/**
 * INTERNAL API
 */
private[remote] class ArteryAeronUdpTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider)
    extends ArteryTransport(_system, _provider) {
  import AeronSource.AeronLifecycle
  import ArteryTransport._
  import Decoder.InboundCompressionAccess
  import FlightRecorderEvents._

  override type LifeCycle = AeronLifecycle

  private[this] val mediaDriver = new AtomicReference[Option[MediaDriver]](None)
  @volatile private[this] var aeron: Aeron = _
  @volatile private[this] var aeronCounterTask: Cancellable = _
  @volatile private[this] var aeronErrorLogTask: Cancellable = _
  @volatile private[this] var aeronErrorLog: AeronErrorLog = _

  private val taskRunner = new TaskRunner(system, settings.Advanced.IdleCpuLevel)

  private def inboundChannel = s"aeron:udp?endpoint=${bindAddress.address.host.get}:${bindAddress.address.port.get}"
  private def outboundChannel(a: Address) = s"aeron:udp?endpoint=${a.host.get}:${a.port.get}"

  override protected def startTransport(): Unit = {
    startMediaDriver()
    startAeron()
    startAeronErrorLog()
    topLevelFlightRecorder.loFreq(Transport_AeronErrorLogStarted, NoMetaData)
    if (settings.LogAeronCounters) {
      startAeronCounterLog()
    }
    taskRunner.start()
    topLevelFlightRecorder.loFreq(Transport_TaskRunnerStarted, NoMetaData)
  }

  private def startMediaDriver(): Unit = {
    if (settings.Advanced.EmbeddedMediaDriver) {
      val driverContext = new MediaDriver.Context
      if (settings.Advanced.AeronDirectoryName.nonEmpty) {
        driverContext.aeronDirectoryName(settings.Advanced.AeronDirectoryName)
      } else {
        // create a random name but include the actor system name for easier debugging
        val uniquePart = UUID.randomUUID().toString
        val randomName = s"${CommonContext.AERON_DIR_PROP_DEFAULT}-${system.name}-$uniquePart"
        driverContext.aeronDirectoryName(randomName)
      }
      driverContext.clientLivenessTimeoutNs(settings.Advanced.ClientLivenessTimeout.toNanos)
      driverContext.imageLivenessTimeoutNs(settings.Advanced.ImageLivenessTimeout.toNanos)
      driverContext.driverTimeoutMs(settings.Advanced.DriverTimeout.toMillis)

      val idleCpuLevel = settings.Advanced.IdleCpuLevel
      if (idleCpuLevel == 10) {
        driverContext
          .threadingMode(ThreadingMode.DEDICATED)
          .conductorIdleStrategy(new BackoffIdleStrategy(1, 1, 1, 1))
          .receiverIdleStrategy(TaskRunner.createIdleStrategy(idleCpuLevel))
          .senderIdleStrategy(TaskRunner.createIdleStrategy(idleCpuLevel))
      } else if (idleCpuLevel == 1) {
        driverContext
          .threadingMode(ThreadingMode.SHARED)
          .sharedIdleStrategy(TaskRunner.createIdleStrategy(idleCpuLevel))
      } else if (idleCpuLevel <= 7) {
        driverContext
          .threadingMode(ThreadingMode.SHARED_NETWORK)
          .sharedNetworkIdleStrategy(TaskRunner.createIdleStrategy(idleCpuLevel))
      } else {
        driverContext
          .threadingMode(ThreadingMode.DEDICATED)
          .receiverIdleStrategy(TaskRunner.createIdleStrategy(idleCpuLevel))
          .senderIdleStrategy(TaskRunner.createIdleStrategy(idleCpuLevel))
      }

      val driver = MediaDriver.launchEmbedded(driverContext)
      log.info("Started embedded media driver in directory [{}]", driver.aeronDirectoryName)
      topLevelFlightRecorder.loFreq(Transport_MediaDriverStarted, driver.aeronDirectoryName())
      if (!mediaDriver.compareAndSet(None, Some(driver))) {
        throw new IllegalStateException("media driver started more than once")
      }
    }
  }

  private def aeronDir: String = mediaDriver.get match {
    case Some(driver) => driver.aeronDirectoryName
    case None         => settings.Advanced.AeronDirectoryName
  }

  private def stopMediaDriver(): Unit = {
    // make sure we only close the driver once or we will crash the JVM
    val maybeDriver = mediaDriver.getAndSet(None)
    maybeDriver.foreach { driver =>
      // this is only for embedded media driver
      try driver.close()
      catch {
        case NonFatal(e) =>
          // don't think driver.close will ever throw, but just in case
          log.warning("Couldn't close Aeron embedded media driver due to [{}]", e)
      }

      try {
        if (settings.Advanced.DeleteAeronDirectory) {
          IoUtil.delete(new File(driver.aeronDirectoryName), false)
          topLevelFlightRecorder.loFreq(Transport_MediaFileDeleted, NoMetaData)
        }
      } catch {
        case NonFatal(e) =>
          log.warning(
            "Couldn't delete Aeron embedded media driver files in [{}] due to [{}]",
            driver.aeronDirectoryName,
            e)
      }
    }
  }

  // TODO: Add FR events
  private def startAeron(): Unit = {
    val ctx = new Aeron.Context

    ctx.driverTimeoutMs(settings.Advanced.DriverTimeout.toMillis)

    ctx.availableImageHandler(new AvailableImageHandler {
      override def onAvailableImage(img: Image): Unit = {
        if (log.isDebugEnabled)
          log.debug(s"onAvailableImage from ${img.sourceIdentity} session ${img.sessionId}")
      }
    })
    ctx.unavailableImageHandler(new UnavailableImageHandler {
      override def onUnavailableImage(img: Image): Unit = {
        if (log.isDebugEnabled)
          log.debug(s"onUnavailableImage from ${img.sourceIdentity} session ${img.sessionId}")

        // freeSessionBuffer in AeronSource FragmentAssembler
        streamMatValues.get.valuesIterator.foreach {
          case InboundStreamMatValues(resourceLife, _) =>
            resourceLife.onUnavailableImage(img.sessionId)
        }
      }
    })

    ctx.errorHandler(new ErrorHandler {
      private val fatalErrorOccured = new AtomicBoolean

      override def onError(cause: Throwable): Unit = {
        cause match {
          case e: ConductorServiceTimeoutException => handleFatalError(e)
          case e: DriverTimeoutException           => handleFatalError(e)
          case _: AeronTerminated                  => // already handled, via handleFatalError
          case _ =>
            log.error(cause, s"Aeron error, $cause")
        }
      }

      private def handleFatalError(cause: Throwable): Unit = {
        if (fatalErrorOccured.compareAndSet(false, true)) {
          if (!isShutdown) {
            log.error(
              cause,
              "Fatal Aeron error {}. Have to terminate ActorSystem because it lost contact with the " +
              "{} Aeron media driver. Possible configuration properties to mitigate the problem are " +
              "'client-liveness-timeout' or 'driver-timeout'. {}",
              Logging.simpleName(cause),
              if (settings.Advanced.EmbeddedMediaDriver) "embedded" else "external",
              cause)
            taskRunner.stop()
            aeronErrorLogTask.cancel()
            if (settings.LogAeronCounters) aeronCounterTask.cancel()
            system.terminate()
            throw new AeronTerminated(cause)
          }
        } else
          throw new AeronTerminated(cause)
      }
    })

    ctx.aeronDirectoryName(aeronDir)
    aeron = Aeron.connect(ctx)
  }

  private def blockUntilChannelActive(): Unit = {
    val aeronLifecyle = streamMatValues.get()(ControlStreamId).lifeCycle

    val waitInterval = 200
    val retries = math.max(1, settings.Bind.BindTimeout.toMillis / waitInterval)
    retry(retries)

    @tailrec def retry(retries: Long): Unit = {
      val status = Await.result(aeronLifecyle.channelEndpointStatus(), settings.Bind.BindTimeout)
      if (status == ChannelEndpointStatus.ACTIVE) {
        log.debug("Inbound channel is now active")
      } else if (status == ChannelEndpointStatus.ERRORED) {
        aeronErrorLog.logErrors(log, 0L)
        stopMediaDriver()
        throw new RemoteTransportException("Inbound Aeron channel is in errored state. See Aeron logs for details.")
      } else if (status == ChannelEndpointStatus.INITIALIZING && retries > 0) {
        Thread.sleep(waitInterval)
        retry(retries - 1)
      } else {
        aeronErrorLog.logErrors(log, 0L)
        stopMediaDriver()
        throw new RemoteTransportException("Timed out waiting for Aeron transport to bind. See Aeoron logs.")
      }
    }
  }

  // TODO Add FR Events
  private def startAeronErrorLog(): Unit = {
    aeronErrorLog = new AeronErrorLog(new File(aeronDir, CncFileDescriptor.CNC_FILE), log)
    val lastTimestamp = new AtomicLong(0L)
    import system.dispatcher
    aeronErrorLogTask = system.scheduler.schedule(3.seconds, 5.seconds) {
      if (!isShutdown) {
        val newLastTimestamp = aeronErrorLog.logErrors(log, lastTimestamp.get)
        lastTimestamp.set(newLastTimestamp + 1)
      }
    }
  }

  private def startAeronCounterLog(): Unit = {
    import system.dispatcher
    aeronCounterTask = system.scheduler.schedule(5.seconds, 5.seconds) {
      if (!isShutdown && log.isDebugEnabled) {
        aeron.countersReader.forEach(new MetaData() {
          def accept(counterId: Int, typeId: Int, keyBuffer: DirectBuffer, label: String): Unit = {
            val value = aeron.countersReader().getCounterValue(counterId)
            log.debug("Aeron Counter {}: {} {}]", counterId, value, label)
          }
        })
      }
    }
  }

  override protected def outboundTransportSink(
      outboundContext: OutboundContext,
      streamId: Int,
      bufferPool: EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]] = {
    val giveUpAfter =
      if (streamId == ControlStreamId) settings.Advanced.GiveUpSystemMessageAfter
      else settings.Advanced.GiveUpMessageAfter
    // TODO: Note that the AssociationState.controlStreamIdleKillSwitch in control stream is not used for the
    // Aeron transport. Would be difficult to handle the Future[Done] materialized value.
    // If we want to stop for Aeron also it is probably easier to stop the publication inside the
    // AeronSink, i.e. not using a KillSwitch.
    Sink.fromGraph(
      new AeronSink(
        outboundChannel(outboundContext.remoteAddress),
        streamId,
        aeron,
        taskRunner,
        bufferPool,
        giveUpAfter,
        createFlightRecorderEventSink()))
  }

  private def aeronSource(streamId: Int, pool: EnvelopeBufferPool): Source[EnvelopeBuffer, AeronSource.AeronLifecycle] =
    Source.fromGraph(
      new AeronSource(
        inboundChannel,
        streamId,
        aeron,
        taskRunner,
        pool,
        createFlightRecorderEventSink(),
        aeronSourceSpinningStrategy))

  private def aeronSourceSpinningStrategy: Int =
    if (settings.Advanced.InboundLanes > 1 || // spinning was identified to be the cause of massive slowdowns with multiple lanes, see #21365
        settings.Advanced.IdleCpuLevel < 5) 0 // also don't spin for small IdleCpuLevels
    else 50 * settings.Advanced.IdleCpuLevel - 240

  override protected def runInboundStreams(): Unit = {
    runInboundControlStream()
    runInboundOrdinaryMessagesStream()

    if (largeMessageChannelEnabled) {
      runInboundLargeMessagesStream()
    }
    blockUntilChannelActive()
  }

  private def runInboundControlStream(): Unit = {
    if (isShutdown) throw ShuttingDown

    val (resourceLife, ctrl, completed) =
      aeronSource(ControlStreamId, envelopeBufferPool)
        .via(inboundFlow(settings, NoInboundCompressions))
        .toMat(inboundControlSink)({ case (a, (c, d)) => (a, c, d) })
        .run()(controlMaterializer)

    attachControlMessageObserver(ctrl)

    updateStreamMatValues(ControlStreamId, resourceLife, completed)
    attachInboundStreamRestart("Inbound control stream", completed, () => runInboundControlStream())
  }

  private def runInboundOrdinaryMessagesStream(): Unit = {
    if (isShutdown) throw ShuttingDown

    val (resourceLife, inboundCompressionAccess, completed) =
      if (inboundLanes == 1) {
        aeronSource(OrdinaryStreamId, envelopeBufferPool)
          .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
          .toMat(inboundSink(envelopeBufferPool))({ case ((a, b), c) => (a, b, c) })
          .run()(materializer)

      } else {
        val laneKillSwitch = KillSwitches.shared("laneKillSwitch")
        val laneSource: Source[InboundEnvelope, (AeronLifecycle, InboundCompressionAccess)] =
          aeronSource(OrdinaryStreamId, envelopeBufferPool)
            .via(laneKillSwitch.flow)
            .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
            .via(Flow.fromGraph(new DuplicateHandshakeReq(inboundLanes, this, system, envelopeBufferPool)))

        val (resourceLife, compressionAccess, laneHub) =
          laneSource
            .toMat(
              Sink.fromGraph(
                new FixedSizePartitionHub[InboundEnvelope](
                  inboundLanePartitioner,
                  inboundLanes,
                  settings.Advanced.InboundHubBufferSize)))({
              case ((a, b), c) => (a, b, c)
            })
            .run()(materializer)

        val lane = inboundSink(envelopeBufferPool)
        val completedValues: Vector[Future[Done]] =
          (0 until inboundLanes).iterator
            .map { _ =>
              laneHub.toMat(lane)(Keep.right).run()(materializer)
            }
            .to(immutable.Vector)

        import system.dispatcher

        // tear down the upstream hub part if downstream lane fails
        // lanes are not completed with success by themselves so we don't have to care about onSuccess
        Future.firstCompletedOf(completedValues).failed.foreach { reason =>
          laneKillSwitch.abort(reason)
        }
        val allCompleted = Future.sequence(completedValues).map(_ => Done)

        (resourceLife, compressionAccess, allCompleted)
      }

    setInboundCompressionAccess(inboundCompressionAccess)

    updateStreamMatValues(OrdinaryStreamId, resourceLife, completed)
    attachInboundStreamRestart("Inbound message stream", completed, () => runInboundOrdinaryMessagesStream())
  }

  private def runInboundLargeMessagesStream(): Unit = {
    if (isShutdown) throw ShuttingDown

    val (resourceLife, completed) = aeronSource(LargeStreamId, largeEnvelopeBufferPool)
      .via(inboundLargeFlow(settings))
      .toMat(inboundSink(largeEnvelopeBufferPool))(Keep.both)
      .run()(materializer)

    updateStreamMatValues(LargeStreamId, resourceLife, completed)
    attachInboundStreamRestart("Inbound large message stream", completed, () => runInboundLargeMessagesStream())
  }

  private def updateStreamMatValues(
      streamId: Int,
      aeronSourceLifecycle: AeronSource.AeronLifecycle,
      completed: Future[Done]): Unit = {
    implicit val ec = materializer.executionContext
    updateStreamMatValues(streamId, InboundStreamMatValues[AeronLifecycle](aeronSourceLifecycle, completed.recover {
      case _ => Done
    }))
  }

  override protected def shutdownTransport(): Future[Done] = {
    import system.dispatcher
    taskRunner.stop().map { _ =>
      topLevelFlightRecorder.loFreq(Transport_Stopped, NoMetaData)
      if (aeronErrorLogTask != null) {
        aeronErrorLogTask.cancel()
        topLevelFlightRecorder.loFreq(Transport_AeronErrorLogTaskStopped, NoMetaData)
      }
      if (aeron != null) aeron.close()
      if (aeronErrorLog != null) aeronErrorLog.close()
      if (mediaDriver.get.isDefined) stopMediaDriver()

      Done
    }
  }

}
