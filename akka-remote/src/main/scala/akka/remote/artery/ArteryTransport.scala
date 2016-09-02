/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.{ DatagramChannel, FileChannel }
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{ AtomicLong, AtomicReference }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
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
import akka.remote.EventPublisher
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransport
import akka.remote.RemotingLifecycleEvent
import akka.remote.ThisActorSystemQuarantinedEvent
import akka.remote.UniqueAddress
import akka.remote.artery.Encoder.ChangeOutboundCompression
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.artery.compress._
import akka.remote.artery.compress.CompressionProtocol.CompressionMessage
import akka.remote.transport.AkkaPduCodec
import akka.remote.transport.AkkaPduProtobufCodec
import akka.stream.AbruptTerminationException
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Helpers.ConfigOps
import akka.util.Helpers.Requiring
import akka.util.OptionVal
import akka.util.WildcardIndex
import io.aeron.Aeron
import io.aeron.AvailableImageHandler
import io.aeron.CncFileDescriptor
import io.aeron.Image
import io.aeron.UnavailableImageHandler
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.exceptions.ConductorServiceTimeoutException
import org.agrona.ErrorHandler
import org.agrona.IoUtil
import org.agrona.concurrent.BackoffIdleStrategy

/**
 * INTERNAL API
 * Inbound API that is used by the stream stages.
 * Separate trait to facilitate testing without real transport.
 */
private[akka] trait InboundContext {
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

}

/**
 * INTERNAL API
 */
private[akka] object AssociationState {
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
private[akka] final class AssociationState(
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
private[akka] trait OutboundContext {
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

  var remaining = associations.flatMap(_.associationState.uniqueRemoteAddressValue)

  val timeoutTask = context.system.scheduler.scheduleOnce(timeout, self, FlushOnShutdown.Timeout)(context.dispatcher)

  override def preStart(): Unit = {
    val msg = ActorSystemTerminating(inboundContext.localAddress)
    associations.foreach { a ⇒ a.send(msg, OptionVal.Some(self), OptionVal.None) }
  }

  override def postStop(): Unit =
    timeoutTask.cancel()

  def receive = {
    case ActorSystemTerminatingAck(from) ⇒
      remaining -= from
      if (remaining.isEmpty) {
        done.trySuccess(Done)
        context.stop(self)
      }
    case FlushOnShutdown.Timeout ⇒
      done.trySuccess(Done)
      context.stop(self)
  }
}

/**
 * INTERNAL API
 */
private[remote] class ArteryTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider)
  extends RemoteTransport(_system, _provider) with InboundContext {
  import FlightRecorderEvents._

  // these vars are initialized once in the start method
  @volatile private[this] var _localAddress: UniqueAddress = _
  @volatile private[this] var _addresses: Set[Address] = _
  @volatile private[this] var materializer: Materializer = _
  @volatile private[this] var controlSubject: ControlMessageSubject = _
  @volatile private[this] var messageDispatcher: MessageDispatcher = _
  private[this] val mediaDriver = new AtomicReference[Option[MediaDriver]](None)
  @volatile private[this] var aeron: Aeron = _
  @volatile private[this] var aeronErrorLogTask: Cancellable = _

  @volatile private[this] var inboundCompressions: Option[InboundCompressions] = None

  override def localAddress: UniqueAddress = _localAddress
  override def defaultAddress: Address = localAddress.address
  override def addresses: Set[Address] = _addresses
  override def localAddressForRemote(remote: Address): Address = defaultAddress
  override val log: LoggingAdapter = Logging(system, getClass.getName)
  val eventPublisher = new EventPublisher(system, log, settings.LifecycleEventsLogLevel)

  private val codec: AkkaPduCodec = AkkaPduProtobufCodec
  private val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")
  @volatile private[this] var _shutdown = false

  private val testStages: CopyOnWriteArrayList[TestManagementApi] = new CopyOnWriteArrayList

  private val handshakeTimeout: FiniteDuration =
    system.settings.config.getMillisDuration("akka.remote.handshake-timeout").requiring(
      _ > Duration.Zero,
      "handshake-timeout must be > 0")

  private val remoteDispatcher = system.dispatchers.lookup(settings.Dispatcher)

  // TODO use WildcardIndex.isEmpty when merged from master
  val largeMessageChannelEnabled =
    !settings.LargeMessageDestinations.wildcardTree.isEmpty || !settings.LargeMessageDestinations.doubleWildcardTree.isEmpty

  private val priorityMessageDestinations =
    WildcardIndex[NotUsed]()
      // These destinations are not defined in configuration because it should not
      // be possible to abuse the control channel
      .insert(Array("system", "remote-watcher"), NotUsed)
      // these belongs to cluster and should come from there
      .insert(Array("system", "cluster", "core", "daemon", "heartbeatSender"), NotUsed)
      .insert(Array("system", "cluster", "heartbeatReceiver"), NotUsed)

  private def inboundChannel = s"aeron:udp?endpoint=${localAddress.address.host.get}:${localAddress.address.port.get}"
  private def outboundChannel(a: Address) = s"aeron:udp?endpoint=${a.host.get}:${a.port.get}"

  private val controlStreamId = 1
  private val ordinaryStreamId = 3
  private val largeStreamId = 4

  private val taskRunner = new TaskRunner(system, settings.Advanced.IdleCpuLevel)

  private val restartCounter = new RestartCounter(settings.Advanced.InboundMaxRestarts, settings.Advanced.InboundRestartTimeout)

  private val envelopePool = new EnvelopeBufferPool(ArteryTransport.MaximumFrameSize, ArteryTransport.MaximumPooledBuffers)
  private val largeEnvelopePool = new EnvelopeBufferPool(ArteryTransport.MaximumLargeFrameSize, ArteryTransport.MaximumPooledBuffers)

  private val inboundEnvelopePool = ReusableInboundEnvelope.createObjectPool(capacity = 16)
  // FIXME capacity of outboundEnvelopePool should probably be derived from the sendQueue capacity
  //       times a factor (for reasonable number of outbound streams)
  private val outboundEnvelopePool = ReusableOutboundEnvelope.createObjectPool(capacity = 3072 * 2)

  val (afrFileChannel, afrFlie, flightRecorder) = initializeFlightRecorder() match {
    case None            ⇒ (None, None, None)
    case Some((c, f, r)) ⇒ (Some(c), Some(f), Some(r))
  }

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

  private val topLevelFREvents =
    createFlightRecorderEventSink(synchr = true)

  private val associationRegistry = new AssociationRegistry(
    remoteAddress ⇒ new Association(
      this,
      materializer,
      remoteAddress,
      controlSubject,
      settings.LargeMessageDestinations,
      priorityMessageDestinations,
      outboundEnvelopePool))

  def settings = provider.remoteSettings.Artery

  override def start(): Unit = {
    startMediaDriver()
    startAeron()
    topLevelFREvents.loFreq(Transport_AeronStarted, NoMetaData)
    startAeronErrorLog()
    topLevelFREvents.loFreq(Transport_AeronErrorLogStarted, NoMetaData)
    taskRunner.start()
    topLevelFREvents.loFreq(Transport_TaskRunnerStarted, NoMetaData)

    val port =
      if (settings.Port == 0) ArteryTransport.autoSelectPort(settings.Hostname)
      else settings.Port

    // TODO: Configure materializer properly
    // TODO: Have a supervisor actor
    _localAddress = UniqueAddress(
      Address(ArteryTransport.ProtocolName, system.name, settings.Hostname, port),
      AddressUidExtension(system).longAddressUid)
    _addresses = Set(_localAddress.address)

    // TODO: This probably needs to be a global value instead of an event as events might rotate out of the log
    topLevelFREvents.loFreq(Transport_UniqueAddressSet, _localAddress.toString().getBytes("US-ASCII"))

    materializer = ActorMaterializer.systemMaterializer(settings.Advanced.MaterializerSettings, "remote", system)

    messageDispatcher = new MessageDispatcher(system, provider)
    topLevelFREvents.loFreq(Transport_MaterializerStarted, NoMetaData)

    runInboundStreams()
    topLevelFREvents.loFreq(Transport_StartupFinished, NoMetaData)

    log.info("Remoting started; listening on address: {}", defaultAddress)
  }

  private lazy val stopMediaDriverShutdownHook = new Thread {
    override def run(): Unit = stopMediaDriver()
  }

  private def startMediaDriver(): Unit = {
    if (settings.Advanced.EmbeddedMediaDriver) {
      val driverContext = new MediaDriver.Context
      if (settings.Advanced.AeronDirectoryName.nonEmpty)
        driverContext.aeronDirectoryName(settings.Advanced.AeronDirectoryName)
      driverContext.clientLivenessTimeoutNs(settings.Advanced.ClientLivenessTimeout.toNanos)
      driverContext.imageLivenessTimeoutNs(settings.Advanced.ImageLivenessTimeoutNs.toNanos)
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
      log.debug("Started embedded media driver in directory [{}]", driver.aeronDirectoryName)
      topLevelFREvents.loFreq(Transport_MediaDriverStarted, driver.aeronDirectoryName().getBytes("US-ASCII"))
      Runtime.getRuntime.addShutdownHook(stopMediaDriverShutdownHook)
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
        }
      } catch {
        case NonFatal(e) ⇒
          log.warning(
            "Couldn't delete Aeron embedded media driver files in [{}] due to [{}]",
            driver.aeronDirectoryName, e.getMessage)
      }
      Try(Runtime.getRuntime.removeShutdownHook(stopMediaDriverShutdownHook))
    }
  }

  // TODO: Add FR events
  private def startAeron(): Unit = {
    val ctx = new Aeron.Context

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
        // FIXME we should call FragmentAssembler.freeSessionBuffer when image is unavailable
      }
    })
    ctx.errorHandler(new ErrorHandler {
      override def onError(cause: Throwable): Unit = {
        cause match {
          case e: ConductorServiceTimeoutException ⇒
            // Timeout between service calls
            log.error(cause, s"Aeron ServiceTimeoutException, ${cause.getMessage}")

          case _ ⇒
            log.error(cause, s"Aeron error, ${cause.getMessage}")
        }
      }
    })

    ctx.aeronDirectoryName(aeronDir)
    aeron = Aeron.connect(ctx)
  }

  // TODO Add FR Events
  private def startAeronErrorLog(): Unit = {
    val errorLog = new AeronErrorLog(new File(aeronDir, CncFileDescriptor.CNC_FILE))
    val lastTimestamp = new AtomicLong(0L)
    import system.dispatcher // FIXME perhaps use another dispatcher for this
    aeronErrorLogTask = system.scheduler.schedule(3.seconds, 5.seconds) {
      if (!isShutdown) {
        val newLastTimestamp = errorLog.logErrors(log, lastTimestamp.get)
        lastTimestamp.set(newLastTimestamp + 1)
      }
    }
  }

  private def runInboundStreams(): Unit = {
    val noCompressions = NoInboundCompressions // TODO possibly enable on other channels too https://github.com/akka/akka/pull/20546/files#r68074082
    val compressions = createInboundCompressions(this)
    inboundCompressions = Some(compressions)

    runInboundControlStream(noCompressions) // TODO should understand compressions too
    runInboundOrdinaryMessagesStream(compressions)
    if (largeMessageChannelEnabled) {
      runInboundLargeMessagesStream()
    }
  }

  private def runInboundControlStream(compression: InboundCompressions): Unit = {
    val (ctrl, completed) =
      if (settings.Advanced.TestMode) {
        val (mgmt, (ctrl, completed)) =
          aeronSource(controlStreamId, envelopePool)
            .via(inboundFlow(compression))
            .viaMat(inboundTestFlow)(Keep.right)
            .toMat(inboundControlSink)(Keep.both)
            .run()(materializer)
        testStages.add(mgmt)
        (ctrl, completed)
      } else {
        aeronSource(controlStreamId, envelopePool)
          .via(inboundFlow(compression))
          .toMat(inboundControlSink)(Keep.right)
          .run()(materializer)
      }

    controlSubject = ctrl

    controlSubject.attach(new ControlMessageObserver {
      override def notify(inboundEnvelope: InboundEnvelope): Unit = {

        inboundEnvelope.message match {
          case m: CompressionMessage ⇒
            import CompressionProtocol._
            m match {
              case ActorRefCompressionAdvertisement(from, table) ⇒
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
              case ActorRefCompressionAdvertisementAck(from, tableVersion) ⇒
                inboundCompressions.foreach(_.confirmActorRefCompressionAdvertisement(from.uid, tableVersion))
              case ClassManifestCompressionAdvertisement(from, table) ⇒
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
              case ClassManifestCompressionAdvertisementAck(from, tableVersion) ⇒
                inboundCompressions.foreach(_.confirmActorRefCompressionAdvertisement(from.uid, tableVersion))
            }

          case Quarantined(from, to) if to == localAddress ⇒
            val lifecycleEvent = ThisActorSystemQuarantinedEvent(localAddress.address, from.address)
            publishLifecycleEvent(lifecycleEvent)
            // quarantine the other system from here
            association(from.address).quarantine(lifecycleEvent.toString, Some(from.uid))

          case _: ActorSystemTerminating ⇒
            inboundEnvelope.sender match {
              case OptionVal.Some(snd) ⇒ snd.tell(ActorSystemTerminatingAck(localAddress), ActorRef.noSender)
              case OptionVal.None      ⇒ log.error("Expected sender for ActorSystemTerminating message")
            }

          case _ ⇒ // not interesting
        }
      }
    })

    attachStreamRestart("Inbound control stream", completed, () ⇒ runInboundControlStream(compression))
  }

  private def runInboundOrdinaryMessagesStream(compression: InboundCompressions): Unit = {
    val completed =
      if (settings.Advanced.TestMode) {
        val (mgmt, c) = aeronSource(ordinaryStreamId, envelopePool)
          .via(inboundFlow(compression))
          .viaMat(inboundTestFlow)(Keep.right)
          .toMat(inboundSink)(Keep.both)
          .run()(materializer)
        testStages.add(mgmt)
        c
      } else {
        aeronSource(ordinaryStreamId, envelopePool)
          .via(inboundFlow(compression))
          .toMat(inboundSink)(Keep.right)
          .run()(materializer)
      }

    attachStreamRestart("Inbound message stream", completed, () ⇒ runInboundOrdinaryMessagesStream(compression))
  }

  private def runInboundLargeMessagesStream(): Unit = {
    val disableCompression = NoInboundCompressions // no compression on large message stream for now

    val completed =
      if (settings.Advanced.TestMode) {
        val (mgmt, c) = aeronSource(largeStreamId, largeEnvelopePool)
          .via(inboundLargeFlow(disableCompression))
          .viaMat(inboundTestFlow)(Keep.right)
          .toMat(inboundSink)(Keep.both)
          .run()(materializer)
        testStages.add(mgmt)
        c
      } else {
        aeronSource(largeStreamId, largeEnvelopePool)
          .via(inboundLargeFlow(disableCompression))
          .toMat(inboundSink)(Keep.right)
          .run()(materializer)
      }

    attachStreamRestart("Inbound large message stream", completed, () ⇒ runInboundLargeMessagesStream())
  }

  private def attachStreamRestart(streamName: String, streamCompleted: Future[Done], restart: () ⇒ Unit): Unit = {
    implicit val ec = materializer.executionContext
    streamCompleted.onFailure {
      case _ if isShutdown               ⇒ // don't restart after shutdown
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
    _shutdown = true
    val allAssociations = associationRegistry.allAssociations
    val flushing: Future[Done] =
      if (allAssociations.isEmpty) Future.successful(Done)
      else {
        val flushingPromise = Promise[Done]()
        system.systemActorOf(FlushOnShutdown.props(flushingPromise, settings.Advanced.ShutdownFlushTimeout,
          this, allAssociations).withDispatcher(settings.Dispatcher), "remoteFlushOnShutdown")
        flushingPromise.future
      }
    implicit val ec = remoteDispatcher
    flushing.recover { case _ ⇒ Done }.map { _ ⇒
      killSwitch.shutdown()

      topLevelFREvents.loFreq(Transport_KillSwitchPulled, NoMetaData)
      if (taskRunner != null) {
        taskRunner.stop()
        topLevelFREvents.loFreq(Transport_Stopped, NoMetaData)
      }
      if (aeronErrorLogTask != null) {
        aeronErrorLogTask.cancel()
        topLevelFREvents.loFreq(Transport_AeronErrorLogTaskStopped, NoMetaData)
      }
      if (aeron != null) aeron.close()
      if (mediaDriver.get.isDefined) {
        stopMediaDriver()
        topLevelFREvents.loFreq(Transport_MediaFileDeleted, NoMetaData)
      }
      topLevelFREvents.loFreq(Transport_FlightRecorderClose, NoMetaData)

      flightRecorder.foreach(_.close())
      afrFileChannel.foreach(_.force(true))
      afrFileChannel.foreach(_.close())
      // TODO: Be smarter about this in tests and make it always-on-for prod
      afrFlie.foreach(_.delete())
      Done
    }
  }

  private[remote] def isShutdown: Boolean = _shutdown

  override def managementCommand(cmd: Any): Future[Boolean] = {
    if (testStages.isEmpty)
      Future.successful(false)
    else {
      import system.dispatcher
      import scala.collection.JavaConverters._
      val allTestStages = testStages.asScala.toVector ++ associationRegistry.allAssociations.flatMap(_.testStages)
      Future.sequence(allTestStages.map(_.send(cmd))).map(_ ⇒ true)
    }
  }

  // InboundContext
  override def sendControl(to: Address, message: ControlMessage) =
    association(to).sendControl(message)

  override def send(message: Any, sender: OptionVal[ActorRef], recipient: RemoteActorRef): Unit = {
    val cached = recipient.cachedAssociation

    val a =
      if (cached ne null) cached
      else {
        val a2 = association(recipient.path.address)
        recipient.cachedAssociation = a2
        a2
      }

    a.send(message, sender, OptionVal.Some(recipient))
  }

  override def association(remoteAddress: Address): Association = {
    require(remoteAddress != localAddress.address, "Attemted association with self address!")
    associationRegistry.association(remoteAddress)
  }

  override def association(uid: Long): OptionVal[Association] =
    associationRegistry.association(uid)

  override def completeHandshake(peer: UniqueAddress): Future[Done] = {
    val a = associationRegistry.setUID(peer)
    a.completeHandshake(peer)
  }

  private def publishLifecycleEvent(event: RemotingLifecycleEvent): Unit =
    eventPublisher.notifyListeners(event)

  override def quarantine(remoteAddress: Address, uid: Option[Int]): Unit = {
    // FIXME change the method signature (old remoting) to include reason and use Long uid?
    association(remoteAddress).quarantine(reason = "", uid.map(_.toLong))
  }

  def outbound(outboundContext: OutboundContext): Sink[OutboundEnvelope, (ChangeOutboundCompression, Future[Done])] =
    createOutboundSink(ordinaryStreamId, outboundContext, envelopePool)

  def outboundLarge(outboundContext: OutboundContext): Sink[OutboundEnvelope, Future[Done]] =
    createOutboundSink(largeStreamId, outboundContext, largeEnvelopePool)
      .mapMaterializedValue { case (_, d) ⇒ d }

  private def createOutboundSink(streamId: Int, outboundContext: OutboundContext,
                                 bufferPool: EnvelopeBufferPool): Sink[OutboundEnvelope, (ChangeOutboundCompression, Future[Done])] = {

    Flow.fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(new OutboundHandshake(system, outboundContext, outboundEnvelopePool, handshakeTimeout,
        settings.Advanced.HandshakeRetryInterval, settings.Advanced.InjectHandshakeInterval))
      .viaMat(createEncoder(bufferPool))(Keep.right)
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), streamId, aeron, taskRunner,
        envelopePool, settings.Advanced.GiveUpSendAfter, createFlightRecorderEventSink()))(Keep.both)
  }

  /**
   * The outbound stream is defined as two parts to be able to add test stage in-between.
   * System messages must not be dropped before the SystemMessageDelivery stage.
   */
  def outboundControlPart1(outboundContext: OutboundContext): Flow[OutboundEnvelope, OutboundEnvelope, SharedKillSwitch] = {
    Flow.fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(new OutboundHandshake(system, outboundContext, outboundEnvelopePool, handshakeTimeout,
        settings.Advanced.HandshakeRetryInterval, settings.Advanced.InjectHandshakeInterval))
      .via(new SystemMessageDelivery(outboundContext, system.deadLetters, settings.Advanced.SystemMessageResendInterval,
        settings.Advanced.SysMsgBufferSize))

    // FIXME we can also add scrubbing stage that would collapse sys msg acks/nacks and remove duplicate Quarantine messages
  }

  def outboundControlPart2(outboundContext: OutboundContext): Sink[OutboundEnvelope, (OutboundControlIngress, Future[Done])] = {
    Flow[OutboundEnvelope]
      .viaMat(new OutboundControlJunction(outboundContext, outboundEnvelopePool))(Keep.right)
      .via(createEncoder(envelopePool))
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), controlStreamId, aeron, taskRunner,
        envelopePool, Duration.Inf, createFlightRecorderEventSink()))(Keep.both)
  }

  private def createInboundCompressions(inboundContext: InboundContext): InboundCompressions =
    new InboundCompressionsImpl(system, inboundContext, settings.Advanced.Compression)

  def createEncoder(pool: EnvelopeBufferPool): Flow[OutboundEnvelope, EnvelopeBuffer, ChangeOutboundCompression] =
    Flow.fromGraph(new Encoder(localAddress, system, outboundEnvelopePool, pool))

  def aeronSource(streamId: Int, pool: EnvelopeBufferPool): Source[EnvelopeBuffer, NotUsed] =
    Source.fromGraph(new AeronSource(inboundChannel, streamId, aeron, taskRunner, pool,
      createFlightRecorderEventSink()))

  val messageDispatcherSink: Sink[InboundEnvelope, Future[Done]] = Sink.foreach[InboundEnvelope] { m ⇒
    messageDispatcher.dispatch(m.recipient.get, m.recipientAddress, m.message, m.sender)
    m match {
      case r: ReusableInboundEnvelope ⇒ inboundEnvelopePool.release(r)
      case _                          ⇒
    }
  }

  def createDecoder(compression: InboundCompressions, bufferPool: EnvelopeBufferPool): Flow[EnvelopeBuffer, InboundEnvelope, NotUsed] = {
    val resolveActorRefWithLocalAddress: String ⇒ InternalActorRef = {
      recipient ⇒ provider.resolveActorRefWithLocalAddress(recipient, localAddress.address)
    }
    Flow.fromGraph(new Decoder(this, system, resolveActorRefWithLocalAddress, compression, bufferPool,
      inboundEnvelopePool))
  }

  def decoder(compression: InboundCompressions): Flow[EnvelopeBuffer, InboundEnvelope, NotUsed] =
    createDecoder(compression, envelopePool)

  def inboundSink: Sink[InboundEnvelope, Future[Done]] =
    Flow[InboundEnvelope]
      .via(new InboundHandshake(this, inControlStream = false))
      .via(new InboundQuarantineCheck(this))
      .toMat(messageDispatcherSink)(Keep.right)

  def inboundFlow(compression: InboundCompressions): Flow[EnvelopeBuffer, InboundEnvelope, NotUsed] = {
    Flow[EnvelopeBuffer]
      .via(killSwitch.flow)
      .via(decoder(compression))
  }

  def inboundLargeFlow(compression: InboundCompressions): Flow[EnvelopeBuffer, InboundEnvelope, NotUsed] = {
    Flow[EnvelopeBuffer]
      .via(killSwitch.flow)
      .via(createDecoder(compression, largeEnvelopePool))
  }

  def inboundControlSink: Sink[InboundEnvelope, (ControlMessageSubject, Future[Done])] = {
    Flow[InboundEnvelope]
      .via(new InboundHandshake(this, inControlStream = true))
      .via(new InboundQuarantineCheck(this))
      .viaMat(new InboundControlJunction)(Keep.right)
      .via(new SystemMessageAcker(this))
      .toMat(messageDispatcherSink)(Keep.both)
  }

  private def initializeFlightRecorder(): Option[(FileChannel, File, FlightRecorder)] = {
    if (settings.Advanced.FlightRecorderEnabled) {
      // TODO: Figure out where to put it, currently using temporary files
      val afrFile = File.createTempFile("artery", ".afr")
      afrFile.deleteOnExit()

      val fileChannel = FlightRecorder.prepareFileForFlightRecorder(afrFile)
      Some((fileChannel, afrFile, new FlightRecorder(fileChannel)))
    } else
      None
  }

  def inboundTestFlow: Flow[InboundEnvelope, InboundEnvelope, TestManagementApi] =
    Flow.fromGraph(new InboundTestStage(this))

  def outboundTestFlow(association: Association): Flow[OutboundEnvelope, OutboundEnvelope, TestManagementApi] =
    Flow.fromGraph(new OutboundTestStage(association))

  /** INTERNAL API: for testing only. */
  private[remote] def triggerCompressionAdvertisements(actorRef: Boolean, manifest: Boolean) = {
    inboundCompressions.foreach {
      case c: InboundCompressionsImpl if actorRef || manifest ⇒
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

  val ProtocolName = "artery"

  val Version = 0
  val MaximumFrameSize = 1024 * 1024
  val MaximumPooledBuffers = 256
  val MaximumLargeFrameSize = MaximumFrameSize * 5

  /**
   * Internal API
   *
   * @return A port that is hopefully available
   */
  private[remote] def autoSelectPort(hostname: String): Int = {
    val socket = DatagramChannel.open().socket()
    socket.bind(new InetSocketAddress(hostname, 0))
    val port = socket.getLocalPort
    socket.close()
    port
  }

}
