/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.{ DatagramChannel, FileChannel }
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{ AtomicLong, AtomicReference }
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal
import akka.Done
import akka.NotUsed
import akka.actor._
import akka.actor.Actor
import akka.actor.Cancellable
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.remote.AddressUidExtension
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransport
import akka.remote.ThisActorSystemQuarantinedEvent
import akka.remote.UniqueAddress
import akka.remote.artery.AeronSource.ResourceLifecycle
import akka.remote.artery.ArteryTransport.ShuttingDown
import akka.remote.artery.Encoder.OutboundCompressionAccess
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.artery.compress._
import akka.remote.artery.compress.CompressionProtocol.CompressionMessage
import akka.remote.transport.ThrottlerTransportAdapter.Blackhole
import akka.remote.transport.ThrottlerTransportAdapter.SetThrottle
import akka.remote.transport.ThrottlerTransportAdapter.Unthrottled
import akka.stream.AbruptTerminationException
import akka.stream.ActorMaterializer
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.OptionVal
import akka.util.WildcardIndex
import io.aeron._
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.exceptions.ConductorServiceTimeoutException
import io.aeron.exceptions.DriverTimeoutException
import org.agrona.ErrorHandler
import org.agrona.IoUtil
import org.agrona.concurrent.BackoffIdleStrategy
import akka.remote.artery.Decoder.InboundCompressionAccess
import akka.remote.transport.TestTransport
import com.typesafe.config.ConfigFactory

/**
 * INTERNAL API
 * Inbound API that is used by the stream stages.
 * Separate trait to facilitate testing without real transport.
 */
private[remote] trait InboundContext {
  /**
   * The local inbound address.
   */
  def localAddress: UniqueAddress

  /**
   * An inbound stage can send control message, e.g. a reply, to the origin
   * address with this method. It will be sent over the control sub-channel.
   */
  def sendControl(to: Address, message: ControlMessage): Unit

  /**
   * Lookup the outbound association for a given address.
   */
  def association(remoteAddress: Address): OutboundContext

  /**
   * Lookup the outbound association for a given UID.
   * Will return `OptionVal.None` if the UID is unknown, i.e.
   * handshake not completed.
   */
  def association(uid: Long): OptionVal[OutboundContext]

  def completeHandshake(peer: UniqueAddress): Future[Done]

  def settings: ArterySettings

}

/**
 * INTERNAL API
 */
private[remote] object AssociationState {
  def apply(): AssociationState =
    new AssociationState(
      incarnation = 1,
      uniqueRemoteAddressPromise = Promise(),
      quarantined = ImmutableLongMap.empty[QuarantinedTimestamp])

  final case class QuarantinedTimestamp(nanoTime: Long) {
    override def toString: String =
      s"Quarantined ${TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - nanoTime)} seconds ago"
  }
}

/**
 * INTERNAL API
 */
private[remote] final class AssociationState(
  val incarnation:                Int,
  val uniqueRemoteAddressPromise: Promise[UniqueAddress],
  val quarantined:                ImmutableLongMap[AssociationState.QuarantinedTimestamp]) {

  import AssociationState.QuarantinedTimestamp

  // doesn't have to be volatile since it's only a cache changed once
  private var uniqueRemoteAddressValueCache: Option[UniqueAddress] = null

  /**
   * Full outbound address with UID for this association.
   * Completed when by the handshake.
   */
  def uniqueRemoteAddress: Future[UniqueAddress] = uniqueRemoteAddressPromise.future

  def uniqueRemoteAddressValue(): Option[UniqueAddress] = {
    if (uniqueRemoteAddressValueCache ne null)
      uniqueRemoteAddressValueCache
    else {
      uniqueRemoteAddress.value match {
        case Some(Success(peer)) ⇒
          uniqueRemoteAddressValueCache = Some(peer)
          uniqueRemoteAddressValueCache
        case _ ⇒ None
      }
    }
  }

  def newIncarnation(remoteAddressPromise: Promise[UniqueAddress]): AssociationState =
    new AssociationState(incarnation + 1, remoteAddressPromise, quarantined)

  def newQuarantined(): AssociationState =
    uniqueRemoteAddressPromise.future.value match {
      case Some(Success(a)) ⇒
        new AssociationState(
          incarnation,
          uniqueRemoteAddressPromise,
          quarantined = quarantined.updated(a.uid, QuarantinedTimestamp(System.nanoTime())))
      case _ ⇒ this
    }

  def isQuarantined(): Boolean = {
    uniqueRemoteAddressValue match {
      case Some(a) ⇒ isQuarantined(a.uid)
      case _       ⇒ false // handshake not completed yet
    }
  }

  def isQuarantined(uid: Long): Boolean = quarantined.contains(uid)

  override def toString(): String = {
    val a = uniqueRemoteAddressPromise.future.value match {
      case Some(Success(a)) ⇒ a
      case Some(Failure(e)) ⇒ s"Failure(${e.getMessage})"
      case None             ⇒ "unknown"
    }
    s"AssociationState($incarnation, $a)"
  }

}

/**
 * INTERNAL API
 * Outbound association API that is used by the stream stages.
 * Separate trait to facilitate testing without real transport.
 */
private[remote] trait OutboundContext {
  /**
   * The local inbound address.
   */
  def localAddress: UniqueAddress

  /**
   * The outbound address for this association.
   */
  def remoteAddress: Address

  def associationState: AssociationState

  def quarantine(reason: String): Unit

  /**
   * An inbound stage can send control message, e.g. a HandshakeReq, to the remote
   * address of this association. It will be sent over the control sub-channel.
   */
  def sendControl(message: ControlMessage): Unit

  /**
   * An outbound stage can listen to control messages
   * via this observer subject.
   */
  def controlSubject: ControlMessageSubject

  def settings: ArterySettings

}

/**
 * INTERNAL API
 */
private[remote] object FlushOnShutdown {
  def props(done: Promise[Done], timeout: FiniteDuration,
            inboundContext: InboundContext, associations: Set[Association]): Props = {
    require(associations.nonEmpty)
    Props(new FlushOnShutdown(done, timeout, inboundContext, associations))
  }

  case object Timeout
}

/**
 * INTERNAL API
 */
private[remote] class FlushOnShutdown(done: Promise[Done], timeout: FiniteDuration,
                                      inboundContext: InboundContext, associations: Set[Association]) extends Actor {

  var remaining = Map.empty[UniqueAddress, Int]

  val timeoutTask = context.system.scheduler.scheduleOnce(timeout, self, FlushOnShutdown.Timeout)(context.dispatcher)

  override def preStart(): Unit = {
    try {
      associations.foreach { a ⇒
        val acksExpected = a.sendTerminationHint(self)
        a.associationState.uniqueRemoteAddressValue() match {
          case Some(address) ⇒ remaining += address → acksExpected
          case None          ⇒ // Ignore, handshake was not completed on this association
        }
      }
      if (remaining.valuesIterator.sum == 0) {
        done.trySuccess(Done)
        context.stop(self)
      }
    } catch {
      case NonFatal(e) ⇒
        // sendTerminationHint may throw
        done.tryFailure(e)
        throw e
    }
  }

  override def postStop(): Unit = {
    timeoutTask.cancel()
    done.trySuccess(Done)
  }

  def receive = {
    case ActorSystemTerminatingAck(from) ⇒
      // Just treat unexpected acks as systems from which zero acks are expected
      val acksRemaining = remaining.getOrElse(from, 0)
      if (acksRemaining <= 1) {
        remaining -= from
      } else {
        remaining = remaining.updated(from, acksRemaining - 1)
      }

      if (remaining.isEmpty)
        context.stop(self)
    case FlushOnShutdown.Timeout ⇒
      context.stop(self)
  }
}

/**
 * INTERNAL API
 */
private[remote] class ArteryTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider)
  extends RemoteTransport(_system, _provider) with InboundContext {
  import ArteryTransport.AeronTerminated
  import ArteryTransport.ShutdownSignal
  import ArteryTransport.InboundStreamMatValues
  import FlightRecorderEvents._

  // these vars are initialized once in the start method
  @volatile private[this] var _localAddress: UniqueAddress = _
  @volatile private[this] var _bindAddress: UniqueAddress = _
  @volatile private[this] var _addresses: Set[Address] = _
  @volatile private[this] var materializer: Materializer = _
  @volatile private[this] var controlMaterializer: Materializer = _
  @volatile private[this] var controlSubject: ControlMessageSubject = _
  @volatile private[this] var messageDispatcher: MessageDispatcher = _
  private[this] val mediaDriver = new AtomicReference[Option[MediaDriver]](None)
  @volatile private[this] var aeron: Aeron = _
  @volatile private[this] var aeronErrorLogTask: Cancellable = _
  @volatile private[this] var areonErrorLog: AeronErrorLog = _

  override val log: LoggingAdapter = Logging(system, getClass.getName)

  val (afrFileChannel, afrFile, flightRecorder) = initializeFlightRecorder() match {
    case None            ⇒ (None, None, None)
    case Some((c, f, r)) ⇒ (Some(c), Some(f), Some(r))
  }

  /**
   * Compression tables must be created once, such that inbound lane restarts don't cause dropping of the tables.
   * However are the InboundCompressions are owned by the Decoder stage, and any call into them must be looped through the Decoder!
   *
   * Use `inboundCompressionAccess` (provided by the materialized `Decoder`) to call into the compression infrastructure.
   */
  private[this] val _inboundCompressions = {
    if (settings.Advanced.Compression.Enabled) {
      val eventSink = createFlightRecorderEventSink(synchr = false)
      new InboundCompressionsImpl(system, this, settings.Advanced.Compression, eventSink)
    } else NoInboundCompressions
  }

  @volatile private[this] var _inboundCompressionAccess: OptionVal[InboundCompressionAccess] = OptionVal.None
  /** Only access compression tables via the CompressionAccess */
  def inboundCompressionAccess: OptionVal[InboundCompressionAccess] = _inboundCompressionAccess

  def bindAddress: UniqueAddress = _bindAddress
  override def localAddress: UniqueAddress = _localAddress
  override def defaultAddress: Address = localAddress.address
  override def addresses: Set[Address] = _addresses
  override def localAddressForRemote(remote: Address): Address = defaultAddress

  private val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

  // keyed by the streamId
  private[this] val streamMatValues = new AtomicReference(Map.empty[Int, InboundStreamMatValues])
  private[this] val hasBeenShutdown = new AtomicBoolean(false)

  private val testState = new SharedTestState

  private val inboundLanes = settings.Advanced.InboundLanes

  // TODO use WildcardIndex.isEmpty when merged from master
  val largeMessageChannelEnabled: Boolean =
    !settings.LargeMessageDestinations.wildcardTree.isEmpty ||
      !settings.LargeMessageDestinations.doubleWildcardTree.isEmpty

  private val priorityMessageDestinations =
    WildcardIndex[NotUsed]()
      // These destinations are not defined in configuration because it should not
      // be possible to abuse the control channel
      .insert(Array("system", "remote-watcher"), NotUsed)
      // these belongs to cluster and should come from there
      .insert(Array("system", "cluster", "core", "daemon", "heartbeatSender"), NotUsed)
      .insert(Array("system", "cluster", "heartbeatReceiver"), NotUsed)

  private def inboundChannel = s"aeron:udp?endpoint=${_bindAddress.address.host.get}:${_bindAddress.address.port.get}"
  private def outboundChannel(a: Address) = s"aeron:udp?endpoint=${a.host.get}:${a.port.get}"

  private val controlStreamId = 1
  private val ordinaryStreamId = 2
  private val largeStreamId = 3

  private val taskRunner = new TaskRunner(system, settings.Advanced.IdleCpuLevel)

  private val restartCounter = new RestartCounter(settings.Advanced.InboundMaxRestarts, settings.Advanced.InboundRestartTimeout)

  private val envelopeBufferPool = new EnvelopeBufferPool(settings.Advanced.MaximumFrameSize, settings.Advanced.BufferPoolSize)
  private val largeEnvelopeBufferPool = new EnvelopeBufferPool(settings.Advanced.MaximumLargeFrameSize, settings.Advanced.LargeBufferPoolSize)

  private val inboundEnvelopePool = ReusableInboundEnvelope.createObjectPool(capacity = 16)
  // The outboundEnvelopePool is shared among all outbound associations
  private val outboundEnvelopePool = ReusableOutboundEnvelope.createObjectPool(capacity =
    settings.Advanced.OutboundMessageQueueSize * settings.Advanced.OutboundLanes * 3)

  private val topLevelFREvents =
    createFlightRecorderEventSink(synchr = true)

  def createFlightRecorderEventSink(synchr: Boolean = false): EventSink = {
    flightRecorder match {
      case Some(f) ⇒
        val eventSink = f.createEventSink()
        if (synchr) new SynchronizedEventSink(eventSink)
        else eventSink
      case None ⇒
        IgnoreEventSink
    }
  }

  private val associationRegistry = new AssociationRegistry(
    remoteAddress ⇒ new Association(
      this,
      materializer,
      controlMaterializer,
      remoteAddress,
      controlSubject,
      settings.LargeMessageDestinations,
      priorityMessageDestinations,
      outboundEnvelopePool))

  override def settings = provider.remoteSettings.Artery

  override def start(): Unit = {
    Runtime.getRuntime.addShutdownHook(shutdownHook)
    startMediaDriver()
    startAeron()
    topLevelFREvents.loFreq(Transport_AeronStarted, NoMetaData)
    startAeronErrorLog()
    topLevelFREvents.loFreq(Transport_AeronErrorLogStarted, NoMetaData)
    taskRunner.start()
    topLevelFREvents.loFreq(Transport_TaskRunnerStarted, NoMetaData)

    val port =
      if (settings.Canonical.Port == 0) {
        if (settings.Bind.Port != 0) settings.Bind.Port // if bind port is set, use bind port instead of random
        else ArteryTransport.autoSelectPort(settings.Canonical.Hostname)
      } else settings.Canonical.Port

    val bindPort = if (settings.Bind.Port == 0) {
      if (settings.Canonical.Port == 0) port // canonical and bind ports are zero. Use random port for both
      else ArteryTransport.autoSelectPort(settings.Bind.Hostname)
    } else settings.Bind.Port

    _localAddress = UniqueAddress(
      Address(ArteryTransport.ProtocolName, system.name, settings.Canonical.Hostname, port),
      AddressUidExtension(system).longAddressUid)
    _addresses = Set(_localAddress.address)

    _bindAddress = UniqueAddress(
      Address(ArteryTransport.ProtocolName, system.name, settings.Bind.Hostname, bindPort),
      AddressUidExtension(system).longAddressUid)

    // TODO: This probably needs to be a global value instead of an event as events might rotate out of the log
    topLevelFREvents.loFreq(Transport_UniqueAddressSet, _localAddress.toString().getBytes("US-ASCII"))

    materializer = ActorMaterializer.systemMaterializer(settings.Advanced.MaterializerSettings, "remote", system)
    controlMaterializer = ActorMaterializer.systemMaterializer(
      settings.Advanced.MaterializerSettings,
      "remoteControl", system)

    messageDispatcher = new MessageDispatcher(system, provider)
    topLevelFREvents.loFreq(Transport_MaterializerStarted, NoMetaData)

    runInboundStreams()
    topLevelFREvents.loFreq(Transport_StartupFinished, NoMetaData)

    log.info("Remoting started; listening on address: [{}] with UID [{}]", localAddress.address, localAddress.uid)
  }

  private lazy val shutdownHook = new Thread {
    override def run(): Unit = {
      if (!hasBeenShutdown.get) {
        val coord = CoordinatedShutdown(system)
        // totalTimeout will be 0 when no tasks registered, so at least 3.seconds
        val totalTimeout = coord.totalTimeout().max(3.seconds)
        if (!coord.jvmHooksLatch.await(totalTimeout.toMillis, TimeUnit.MILLISECONDS))
          log.warning(
            "CoordinatedShutdown took longer than [{}]. Shutting down [{}] via shutdownHook",
            totalTimeout, localAddress)
        else
          log.debug("Shutting down [{}] via shutdownHook", localAddress)
        if (hasBeenShutdown.compareAndSet(false, true)) {
          Await.result(internalShutdown(), settings.Advanced.DriverTimeout + 3.seconds)
        }
      }
    }
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
      topLevelFREvents.loFreq(Transport_MediaDriverStarted, driver.aeronDirectoryName().getBytes("US-ASCII"))
      if (!mediaDriver.compareAndSet(None, Some(driver))) {
        throw new IllegalStateException("media driver started more than once")
      }
    }
  }

  private def aeronDir: String = mediaDriver.get match {
    case Some(driver) ⇒ driver.aeronDirectoryName
    case None         ⇒ settings.Advanced.AeronDirectoryName
  }

  private def stopMediaDriver(): Unit = {
    // make sure we only close the driver once or we will crash the JVM
    val maybeDriver = mediaDriver.getAndSet(None)
    maybeDriver.foreach { driver ⇒
      // this is only for embedded media driver
      driver.close()

      try {
        if (settings.Advanced.DeleteAeronDirectory) {
          IoUtil.delete(new File(driver.aeronDirectoryName), false)
          topLevelFREvents.loFreq(Transport_MediaFileDeleted, NoMetaData)
        }
      } catch {
        case NonFatal(e) ⇒
          log.warning(
            "Couldn't delete Aeron embedded media driver files in [{}] due to [{}]",
            driver.aeronDirectoryName, e.getMessage)
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
          case InboundStreamMatValues(resourceLife, _) ⇒ resourceLife.onUnavailableImage(img.sessionId)
        }
      }
    })

    ctx.errorHandler(new ErrorHandler {
      private val fatalErrorOccured = new AtomicBoolean

      override def onError(cause: Throwable): Unit = {
        cause match {
          case e: ConductorServiceTimeoutException ⇒ handleFatalError(e)
          case e: DriverTimeoutException           ⇒ handleFatalError(e)
          case _: AeronTerminated                  ⇒ // already handled, via handleFatalError
          case _ ⇒
            log.error(cause, s"Aeron error, ${cause.getMessage}")
        }
      }

      private def handleFatalError(cause: Throwable): Unit = {
        if (fatalErrorOccured.compareAndSet(false, true)) {
          if (!isShutdown) {
            log.error(cause, "Fatal Aeron error {}. Have to terminate ActorSystem because it lost contact with the " +
              "{} Aeron media driver. Possible configuration properties to mitigate the problem are " +
              "'client-liveness-timeout' or 'driver-timeout'. {}",
              Logging.simpleName(cause),
              if (settings.Advanced.EmbeddedMediaDriver) "embedded" else "external",
              cause.getMessage)
            taskRunner.stop()
            aeronErrorLogTask.cancel()
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

  // TODO Add FR Events
  private def startAeronErrorLog(): Unit = {
    areonErrorLog = new AeronErrorLog(new File(aeronDir, CncFileDescriptor.CNC_FILE))
    val lastTimestamp = new AtomicLong(0L)
    import system.dispatcher
    aeronErrorLogTask = system.scheduler.schedule(3.seconds, 5.seconds) {
      if (!isShutdown) {
        val newLastTimestamp = areonErrorLog.logErrors(log, lastTimestamp.get)
        lastTimestamp.set(newLastTimestamp + 1)
      }
    }
  }

  private def runInboundStreams(): Unit = {
    runInboundControlStream()
    runInboundOrdinaryMessagesStream()

    if (largeMessageChannelEnabled) {
      runInboundLargeMessagesStream()
    }
  }

  private def runInboundControlStream(): Unit = {
    if (isShutdown) throw ShuttingDown
    val (resourceLife, ctrl, completed) =
      aeronSource(controlStreamId, envelopeBufferPool)
        .via(inboundFlow(settings, NoInboundCompressions))
        .toMat(inboundControlSink)({ case (a, (c, d)) ⇒ (a, c, d) })
        .run()(controlMaterializer)

    controlSubject = ctrl

    controlSubject.attach(new ControlMessageObserver {
      override def notify(inboundEnvelope: InboundEnvelope): Unit = {
        try {
          inboundEnvelope.message match {
            case m: CompressionMessage ⇒
              import CompressionProtocol._
              m match {
                case ActorRefCompressionAdvertisement(from, table) ⇒
                  if (table.originUid == localAddress.uid) {
                    log.debug("Incoming ActorRef compression advertisement from [{}], table: [{}]", from, table)
                    val a = association(from.address)
                    // make sure uid is same for active association
                    if (a.associationState.uniqueRemoteAddressValue().contains(from)) {
                      import system.dispatcher
                      a.changeActorRefCompression(table).foreach { _ ⇒
                        a.sendControl(ActorRefCompressionAdvertisementAck(localAddress, table.version))
                        system.eventStream.publish(Events.ReceivedActorRefCompressionTable(from, table))
                      }
                    }
                  } else
                    log.debug(
                      "Discarding incoming ActorRef compression advertisement from [{}] that was " +
                        "prepared for another incarnation with uid [{}] than current uid [{}], table: [{}]",
                      from, table.originUid, localAddress.uid, table)
                case ack: ActorRefCompressionAdvertisementAck ⇒
                  inboundCompressionAccess match {
                    case OptionVal.Some(access) ⇒ access.confirmActorRefCompressionAdvertisementAck(ack)
                    case _ ⇒
                      log.debug(s"Received {} version: [{}] however no inbound compression access was present. " +
                        s"ACK will not take effect, however it will be redelivered and likely to apply then.", Logging.simpleName(ack), ack.tableVersion)
                  }

                case ClassManifestCompressionAdvertisement(from, table) ⇒
                  if (table.originUid == localAddress.uid) {
                    log.debug("Incoming Class Manifest compression advertisement from [{}], table: [{}]", from, table)
                    val a = association(from.address)
                    // make sure uid is same for active association
                    if (a.associationState.uniqueRemoteAddressValue().contains(from)) {
                      import system.dispatcher
                      a.changeClassManifestCompression(table).foreach { _ ⇒
                        a.sendControl(ClassManifestCompressionAdvertisementAck(localAddress, table.version))
                        system.eventStream.publish(Events.ReceivedClassManifestCompressionTable(from, table))
                      }
                    }
                  } else
                    log.debug(
                      "Discarding incoming Class Manifest compression advertisement from [{}] that was " +
                        "prepared for another incarnation with uid [{}] than current uid [{}], table: [{}]",
                      from, table.originUid, localAddress.uid, table)
                case ack: ClassManifestCompressionAdvertisementAck ⇒
                  inboundCompressionAccess match {
                    case OptionVal.Some(access) ⇒ access.confirmClassManifestCompressionAdvertisementAck(ack)
                    case _ ⇒
                      log.debug(s"Received {} version: [{}] however no inbound compression access was present. " +
                        s"ACK will not take effect, however it will be redelivered and likely to apply then.", Logging.simpleName(ack), ack.tableVersion)
                  }
              }

            case Quarantined(from, to) if to == localAddress ⇒
              // Don't quarantine the other system here, since that will result cluster member removal
              // and can result in forming two separate clusters (cluster split).
              // Instead, the downing strategy should act on ThisActorSystemQuarantinedEvent, e.g.
              // use it as a STONITH signal.
              val lifecycleEvent = ThisActorSystemQuarantinedEvent(localAddress.address, from.address)
              system.eventStream.publish(lifecycleEvent)

            case _ ⇒ // not interesting
          }
        } catch {
          case ShuttingDown ⇒ // silence it
        }
      }
    })

    updateStreamMatValues(controlStreamId, resourceLife, completed)
    attachStreamRestart("Inbound control stream", completed, () ⇒ runInboundControlStream())
  }

  private def runInboundOrdinaryMessagesStream(): Unit = {
    if (isShutdown) throw ShuttingDown

    val (resourceLife, inboundCompressionAccesses, completed) =
      if (inboundLanes == 1) {
        aeronSource(ordinaryStreamId, envelopeBufferPool)
          .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
          .toMat(inboundSink(envelopeBufferPool))({ case ((a, b), c) ⇒ (a, b, c) })
          .run()(materializer)

      } else {
        val hubKillSwitch = KillSwitches.shared("hubKillSwitch")
        val source: Source[InboundEnvelope, (ResourceLifecycle, InboundCompressionAccess)] =
          aeronSource(ordinaryStreamId, envelopeBufferPool)
            .via(hubKillSwitch.flow)
            .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)

        // select lane based on destination, to preserve message order
        val partitioner: InboundEnvelope ⇒ Int = env ⇒ {
          env.recipient match {
            case OptionVal.Some(r) ⇒ math.abs(r.path.uid) % inboundLanes
            case OptionVal.None    ⇒ 0
          }
        }

        val (resourceLife, compressionAccess, hub) =
          source
            .toMat(Sink.fromGraph(new FixedSizePartitionHub[InboundEnvelope](partitioner, inboundLanes,
              settings.Advanced.InboundHubBufferSize)))({ case ((a, b), c) ⇒ (a, b, c) })
            .run()(materializer)

        val lane = inboundSink(envelopeBufferPool)
        val completedValues: Vector[Future[Done]] =
          (0 until inboundLanes).map { _ ⇒
            hub.toMat(lane)(Keep.right).run()(materializer)
          }(collection.breakOut)

        import system.dispatcher
        val completed = Future.sequence(completedValues).map(_ ⇒ Done)

        // tear down the upstream hub part if downstream lane fails
        // lanes are not completed with success by themselves so we don't have to care about onSuccess
        completed.failed.foreach { reason ⇒ hubKillSwitch.abort(reason) }

        (resourceLife, compressionAccess, completed)
      }

    _inboundCompressionAccess = OptionVal(inboundCompressionAccesses)

    updateStreamMatValues(ordinaryStreamId, resourceLife, completed)
    attachStreamRestart("Inbound message stream", completed, () ⇒ runInboundOrdinaryMessagesStream())
  }

  private def runInboundLargeMessagesStream(): Unit = {
    if (isShutdown) throw ShuttingDown

    val (resourceLife, completed) = aeronSource(largeStreamId, largeEnvelopeBufferPool)
      .via(inboundLargeFlow(settings))
      .toMat(inboundSink(largeEnvelopeBufferPool))(Keep.both)
      .run()(materializer)

    updateStreamMatValues(largeStreamId, resourceLife, completed)
    attachStreamRestart("Inbound large message stream", completed, () ⇒ runInboundLargeMessagesStream())
  }

  private def attachStreamRestart(streamName: String, streamCompleted: Future[Done], restart: () ⇒ Unit): Unit = {
    implicit val ec = materializer.executionContext
    streamCompleted.failed.foreach {
      case ShutdownSignal     ⇒ // shutdown as expected
      case _: AeronTerminated ⇒ // shutdown already in progress
      case cause if isShutdown ⇒
        // don't restart after shutdown, but log some details so we notice
        log.error(cause, s"{} failed after shutdown. {}", streamName, cause.getMessage)
      case _: AbruptTerminationException ⇒ // ActorSystem shutdown
      case cause ⇒
        if (restartCounter.restart()) {
          log.error(cause, "{} failed. Restarting it. {}", streamName, cause.getMessage)
          restart()
        } else {
          log.error(cause, "{} failed and restarted {} times within {} seconds. Terminating system. {}",
            streamName, settings.Advanced.InboundMaxRestarts, settings.Advanced.InboundRestartTimeout.toSeconds, cause.getMessage)
          system.terminate()
        }
    }
  }

  override def shutdown(): Future[Done] = {
    if (hasBeenShutdown.compareAndSet(false, true)) {
      log.debug("Shutting down [{}]", localAddress)
      val allAssociations = associationRegistry.allAssociations
      val flushing: Future[Done] =
        if (allAssociations.isEmpty) Future.successful(Done)
        else {
          val flushingPromise = Promise[Done]()
          system.systemActorOf(FlushOnShutdown.props(flushingPromise, settings.Advanced.ShutdownFlushTimeout,
            this, allAssociations), "remoteFlushOnShutdown")
          flushingPromise.future
        }
      implicit val ec = system.dispatcher
      flushing.recover { case _ ⇒ Done }.flatMap(_ ⇒ internalShutdown())
    } else {
      Future.successful(Done)
    }
  }

  private def internalShutdown(): Future[Done] = {
    import system.dispatcher

    killSwitch.abort(ShutdownSignal)
    topLevelFREvents.loFreq(Transport_KillSwitchPulled, NoMetaData)
    for {
      _ ← streamsCompleted
      _ ← taskRunner.stop()
    } yield {
      topLevelFREvents.loFreq(Transport_Stopped, NoMetaData)

      // no need to explicitly shut down the contained access since it's lifecycle is bound to the Decoder
      _inboundCompressionAccess = OptionVal.None

      if (aeronErrorLogTask != null) {
        aeronErrorLogTask.cancel()
        topLevelFREvents.loFreq(Transport_AeronErrorLogTaskStopped, NoMetaData)
      }
      if (aeron != null) aeron.close()
      if (areonErrorLog != null) areonErrorLog.close()
      if (mediaDriver.get.isDefined) {
        stopMediaDriver()

      }
      topLevelFREvents.loFreq(Transport_FlightRecorderClose, NoMetaData)

      flightRecorder.foreach(_.close())
      afrFileChannel.foreach(_.force(true))
      afrFileChannel.foreach(_.close())
      Done
    }
  }

  private def updateStreamMatValues(streamId: Int, aeronSourceLifecycle: AeronSource.ResourceLifecycle, completed: Future[Done]): Unit = {
    implicit val ec = materializer.executionContext
    updateStreamMatValues(streamId, InboundStreamMatValues(aeronSourceLifecycle, completed.recover { case _ ⇒ Done }))
  }

  @tailrec private def updateStreamMatValues(streamId: Int, values: InboundStreamMatValues): Unit = {
    val prev = streamMatValues.get()
    if (!streamMatValues.compareAndSet(prev, prev + (streamId → values))) {
      updateStreamMatValues(streamId, values)
    }
  }

  /**
   * Exposed for orderly shutdown purposes, can not be trusted except for during shutdown as streams may restart.
   * Will complete successfully even if one of the stream completion futures failed
   */
  private def streamsCompleted: Future[Done] = {
    implicit val ec = system.dispatcher
    for {
      _ ← Future.traverse(associationRegistry.allAssociations)(_.streamsCompleted)
      _ ← Future.sequence(streamMatValues.get().valuesIterator.map {
        case InboundStreamMatValues(_, done) ⇒ done
      })
    } yield Done
  }

  private[remote] def isShutdown: Boolean = hasBeenShutdown.get()

  override def managementCommand(cmd: Any): Future[Boolean] = {
    cmd match {
      case SetThrottle(address, direction, Blackhole) ⇒
        testState.blackhole(localAddress.address, address, direction)
      case SetThrottle(address, direction, Unthrottled) ⇒
        testState.passThrough(localAddress.address, address, direction)
      case TestManagementCommands.FailInboundStreamOnce(ex) ⇒
        testState.failInboundStreamOnce(ex)
    }
    Future.successful(true)
  }

  // InboundContext
  override def sendControl(to: Address, message: ControlMessage) =
    try {
      association(to).sendControl(message)
    } catch {
      case ShuttingDown ⇒ // silence it
    }

  override def send(message: Any, sender: OptionVal[ActorRef], recipient: RemoteActorRef): Unit =
    try {
      val cached = recipient.cachedAssociation

      val a =
        if (cached ne null) cached
        else {
          val a2 = association(recipient.path.address)
          recipient.cachedAssociation = a2
          a2
        }

      a.send(message, sender, OptionVal.Some(recipient))
    } catch {
      case ShuttingDown ⇒ // silence it
    }

  override def association(remoteAddress: Address): Association = {
    require(remoteAddress != localAddress.address, "Attempted association with self address!")
    // only look at isShutdown if there wasn't already an association
    // races but better than nothing
    associationRegistry.association(remoteAddress)
  }

  override def association(uid: Long): OptionVal[Association] =
    associationRegistry.association(uid)

  override def completeHandshake(peer: UniqueAddress): Future[Done] = {
    try {
      val a = associationRegistry.setUID(peer)
      a.completeHandshake(peer)
    } catch {
      case ShuttingDown ⇒ Future.successful(Done) // silence it
    }
  }

  override def quarantine(remoteAddress: Address, uid: Option[Long], reason: String): Unit = {
    try {
      association(remoteAddress).quarantine(reason, uid)
    } catch {
      case ShuttingDown ⇒ // silence it
    }
  }

  def outboundLarge(outboundContext: OutboundContext): Sink[OutboundEnvelope, Future[Done]] =
    createOutboundSink(largeStreamId, outboundContext, largeEnvelopeBufferPool)
      .mapMaterializedValue { case (_, d) ⇒ d }

  def outbound(outboundContext: OutboundContext): Sink[OutboundEnvelope, (OutboundCompressionAccess, Future[Done])] =
    createOutboundSink(ordinaryStreamId, outboundContext, envelopeBufferPool)

  private def createOutboundSink(streamId: Int, outboundContext: OutboundContext,
                                 bufferPool: EnvelopeBufferPool): Sink[OutboundEnvelope, (OutboundCompressionAccess, Future[Done])] = {

    outboundLane(outboundContext, bufferPool)
      .toMat(aeronSink(outboundContext, streamId, bufferPool))(Keep.both)
  }

  def aeronSink(outboundContext: OutboundContext): Sink[EnvelopeBuffer, Future[Done]] =
    aeronSink(outboundContext, ordinaryStreamId, envelopeBufferPool)

  private def aeronSink(outboundContext: OutboundContext, streamId: Int,
                        bufferPool: EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]] = {

    val giveUpAfter =
      if (streamId == controlStreamId) settings.Advanced.GiveUpSystemMessageAfter
      else settings.Advanced.GiveUpMessageAfter
    Sink.fromGraph(new AeronSink(outboundChannel(outboundContext.remoteAddress), streamId, aeron, taskRunner,
      bufferPool, giveUpAfter, createFlightRecorderEventSink()))
  }

  def outboundLane(outboundContext: OutboundContext): Flow[OutboundEnvelope, EnvelopeBuffer, OutboundCompressionAccess] =
    outboundLane(outboundContext, envelopeBufferPool)

  private def outboundLane(
    outboundContext: OutboundContext,
    bufferPool:      EnvelopeBufferPool): Flow[OutboundEnvelope, EnvelopeBuffer, OutboundCompressionAccess] = {

    Flow.fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(new OutboundHandshake(system, outboundContext, outboundEnvelopePool, settings.Advanced.HandshakeTimeout,
        settings.Advanced.HandshakeRetryInterval, settings.Advanced.InjectHandshakeInterval))
      .viaMat(createEncoder(bufferPool))(Keep.right)
  }

  def outboundControl(outboundContext: OutboundContext): Sink[OutboundEnvelope, (OutboundControlIngress, Future[Done])] = {

    Flow.fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(new OutboundHandshake(system, outboundContext, outboundEnvelopePool, settings.Advanced.HandshakeTimeout,
        settings.Advanced.HandshakeRetryInterval, settings.Advanced.InjectHandshakeInterval))
      .via(new SystemMessageDelivery(outboundContext, system.deadLetters, settings.Advanced.SystemMessageResendInterval,
        settings.Advanced.SysMsgBufferSize))
      // note that System messages must not be dropped before the SystemMessageDelivery stage
      .via(outboundTestFlow(outboundContext))
      .viaMat(new OutboundControlJunction(outboundContext, outboundEnvelopePool))(Keep.right)
      .via(createEncoder(envelopeBufferPool))
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), controlStreamId, aeron, taskRunner,
        envelopeBufferPool, Duration.Inf, createFlightRecorderEventSink()))(Keep.both)

    // TODO we can also add scrubbing stage that would collapse sys msg acks/nacks and remove duplicate Quarantine messages
  }

  def createEncoder(pool: EnvelopeBufferPool): Flow[OutboundEnvelope, EnvelopeBuffer, OutboundCompressionAccess] =
    Flow.fromGraph(new Encoder(localAddress, system, outboundEnvelopePool, pool, settings.LogSend))

  def aeronSource(streamId: Int, pool: EnvelopeBufferPool): Source[EnvelopeBuffer, AeronSource.ResourceLifecycle] =
    Source.fromGraph(new AeronSource(inboundChannel, streamId, aeron, taskRunner, pool,
      createFlightRecorderEventSink(), aeronSourceSpinningStrategy))

  private def aeronSourceSpinningStrategy: Int =
    if (settings.Advanced.InboundLanes > 1 || // spinning was identified to be the cause of massive slowdowns with multiple lanes, see #21365
      settings.Advanced.IdleCpuLevel < 5) 0 // also don't spin for small IdleCpuLevels
    else 50 * settings.Advanced.IdleCpuLevel - 240

  val messageDispatcherSink: Sink[InboundEnvelope, Future[Done]] = Sink.foreach[InboundEnvelope] { m ⇒
    messageDispatcher.dispatch(m)
    m match {
      case r: ReusableInboundEnvelope ⇒ inboundEnvelopePool.release(r)
      case _                          ⇒
    }
  }

  def createDecoder(settings: ArterySettings, compressions: InboundCompressions, bufferPool: EnvelopeBufferPool): Flow[EnvelopeBuffer, InboundEnvelope, InboundCompressionAccess] =
    Flow.fromGraph(new Decoder(this, system, localAddress, settings, bufferPool, compressions, inboundEnvelopePool))

  def createDeserializer(bufferPool: EnvelopeBufferPool): Flow[InboundEnvelope, InboundEnvelope, NotUsed] =
    Flow.fromGraph(new Deserializer(this, system, bufferPool))

  // Checks for termination hint messages and sends an ACK for those (not processing them further)
  // Purpose of this stage is flushing, the sender can wait for the ACKs up to try flushing
  // pending messages.
  def terminationHintReplier(): Flow[InboundEnvelope, InboundEnvelope, NotUsed] = {
    Flow[InboundEnvelope].filter { envelope ⇒
      envelope.message match {
        case _: ActorSystemTerminating ⇒
          envelope.sender match {
            case OptionVal.Some(snd) ⇒ snd.tell(ActorSystemTerminatingAck(localAddress), ActorRef.noSender)
            case OptionVal.None      ⇒ log.error("Expected sender for ActorSystemTerminating message")
          }
          false
        case _ ⇒ true
      }
    }
  }

  def inboundSink(bufferPool: EnvelopeBufferPool): Sink[InboundEnvelope, Future[Done]] =
    Flow[InboundEnvelope]
      .via(createDeserializer(bufferPool))
      .via(new InboundTestStage(this, testState, settings.Advanced.TestMode))
      .via(terminationHintReplier())
      .via(new InboundHandshake(this, inControlStream = false))
      .via(new InboundQuarantineCheck(this))
      .toMat(messageDispatcherSink)(Keep.right)

  def inboundFlow(settings: ArterySettings, compressions: InboundCompressions): Flow[EnvelopeBuffer, InboundEnvelope, InboundCompressionAccess] = {
    Flow[EnvelopeBuffer]
      .via(killSwitch.flow)
      .viaMat(createDecoder(settings, compressions, envelopeBufferPool))(Keep.right)
  }

  // large messages flow does not use compressions, since the message size dominates the size anyway
  def inboundLargeFlow(settings: ArterySettings): Flow[EnvelopeBuffer, InboundEnvelope, NotUsed] = {
    Flow[EnvelopeBuffer]
      .via(killSwitch.flow)
      .via(createDecoder(settings, NoInboundCompressions, largeEnvelopeBufferPool))
  }

  def inboundControlSink: Sink[InboundEnvelope, (ControlMessageSubject, Future[Done])] = {
    Flow[InboundEnvelope]
      .via(createDeserializer(envelopeBufferPool))
      .via(new InboundTestStage(this, testState, settings.Advanced.TestMode))
      .via(terminationHintReplier())
      .via(new InboundHandshake(this, inControlStream = true))
      .via(new InboundQuarantineCheck(this))
      .viaMat(new InboundControlJunction)(Keep.right)
      .via(new SystemMessageAcker(this))
      .toMat(messageDispatcherSink)(Keep.both)
  }

  private def initializeFlightRecorder(): Option[(FileChannel, Path, FlightRecorder)] = {
    if (settings.Advanced.FlightRecorderEnabled) {
      val afrFile = FlightRecorder.createFlightRecorderFile(settings.Advanced.FlightRecorderDestination)
      log.info("Flight recorder enabled, output can be found in '{}'", afrFile)

      val fileChannel = FlightRecorder.prepareFileForFlightRecorder(afrFile)
      Some((fileChannel, afrFile, new FlightRecorder(fileChannel)))
    } else
      None
  }

  def outboundTestFlow(outboundContext: OutboundContext): Flow[OutboundEnvelope, OutboundEnvelope, NotUsed] =
    Flow.fromGraph(new OutboundTestStage(outboundContext, testState, settings.Advanced.TestMode))

  /** INTERNAL API: for testing only. */
  private[remote] def triggerCompressionAdvertisements(actorRef: Boolean, manifest: Boolean) = {
    inboundCompressionAccess match {
      case OptionVal.Some(c) if actorRef || manifest ⇒
        log.info("Triggering compression table advertisement for {}", c)
        if (actorRef) c.runNextActorRefAdvertisement()
        if (manifest) c.runNextClassManifestAdvertisement()
      case _ ⇒
    }
  }

}

/**
 * INTERNAL API
 */
private[remote] object ArteryTransport {

  val ProtocolName = "akka"

  val Version: Byte = 0

  class AeronTerminated(e: Throwable) extends RuntimeException(e)

  object ShutdownSignal extends RuntimeException with NoStackTrace

  // thrown when the transport is shutting down and something triggers a new association
  object ShuttingDown extends RuntimeException with NoStackTrace

  final case class InboundStreamMatValues(
    aeronSourceLifecycle: AeronSource.ResourceLifecycle,
    completed:            Future[Done])

  def autoSelectPort(hostname: String): Int = {
    val socket = DatagramChannel.open().socket()
    socket.bind(new InetSocketAddress(hostname, 0))
    val port = socket.getLocalPort
    socket.close()
    port
  }

}
