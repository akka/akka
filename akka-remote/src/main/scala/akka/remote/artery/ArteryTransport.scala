/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import java.util.concurrent.TimeUnit.MICROSECONDS
import akka.remote.artery.compress.CompressionProtocol.CompressionMessage

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.Done
import akka.NotUsed
import akka.actor._
import akka.actor.Cancellable
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.remote.AddressUidExtension
import akka.remote.EventPublisher
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteSettings
import akka.remote.RemoteTransport
import akka.remote.RemotingLifecycleEvent
import akka.remote.ThisActorSystemQuarantinedEvent
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.transport.AkkaPduCodec
import akka.remote.transport.AkkaPduProtobufCodec
import akka.remote.artery.compress._
import akka.stream.AbruptTerminationException
import akka.stream.ActorMaterializer
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Helpers.ConfigOps
import akka.util.Helpers.Requiring
import akka.util.WildcardTree
import io.aeron.Aeron
import io.aeron.AvailableImageHandler
import io.aeron.Image
import io.aeron.UnavailableImageHandler
import io.aeron.driver.MediaDriver
import io.aeron.exceptions.ConductorServiceTimeoutException
import org.agrona.ErrorHandler
import org.agrona.IoUtil
import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.{ DatagramChannel, FileChannel }

import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import io.aeron.CncFileDescriptor
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConverters._
import akka.stream.ActorMaterializerSettings

import scala.annotation.tailrec
import akka.util.OptionVal
import io.aeron.driver.ThreadingMode
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.BusySpinIdleStrategy

import scala.util.control.NonFatal
import akka.actor.Props
import akka.actor.Actor

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

  def completeHandshake(peer: UniqueAddress): Unit

}

/**
 * INTERNAL API
 */
private[akka] object AssociationState {
  def apply(): AssociationState =
    new AssociationState(
      incarnation = 1,
      uniqueRemoteAddressPromise = Promise(),
      quarantined = ImmutableLongMap.empty[QuarantinedTimestamp],
      outboundCompressions = NoOutboundCompressions)

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
  val quarantined:                ImmutableLongMap[AssociationState.QuarantinedTimestamp],
  val outboundCompressions:       OutboundCompressions) {

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

  def withCompression(compression: OutboundCompressions) =
    new AssociationState(incarnation, uniqueRemoteAddressPromise, quarantined, compression)

  def newIncarnation(remoteAddressPromise: Promise[UniqueAddress], compression: OutboundCompressions): AssociationState =
    new AssociationState(incarnation + 1, remoteAddressPromise, quarantined, compression)

  def newQuarantined(): AssociationState =
    uniqueRemoteAddressPromise.future.value match {
      case Some(Success(a)) ⇒
        new AssociationState(
          incarnation,
          uniqueRemoteAddressPromise,
          quarantined = quarantined.updated(a.uid, QuarantinedTimestamp(System.nanoTime())),
          outboundCompressions = NoOutboundCompressions) // after quarantine no compression needed anymore, drop it
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
  @volatile private[this] var mediaDriver: Option[MediaDriver] = None
  @volatile private[this] var aeron: Aeron = _
  @volatile private[this] var aeronErrorLogTask: Cancellable = _

  @volatile private[this] var inboundCompressions: Option[InboundCompressions] = None

  override def localAddress: UniqueAddress = _localAddress
  override def defaultAddress: Address = localAddress.address
  override def addresses: Set[Address] = _addresses
  override def localAddressForRemote(remote: Address): Address = defaultAddress
  override val log: LoggingAdapter = Logging(system, getClass.getName)
  val eventPublisher = new EventPublisher(system, log, remoteSettings.RemoteLifecycleEventsLogLevel)

  private val codec: AkkaPduCodec = AkkaPduProtobufCodec
  private val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")
  @volatile private[this] var _shutdown = false

  private val testStages: CopyOnWriteArrayList[TestManagementApi] = new CopyOnWriteArrayList

  // FIXME config
  private val systemMessageResendInterval: FiniteDuration = 1.second
  private val handshakeRetryInterval: FiniteDuration = 1.second
  private val handshakeTimeout: FiniteDuration =
    system.settings.config.getMillisDuration("akka.remote.handshake-timeout").requiring(
      _ > Duration.Zero,
      "handshake-timeout must be > 0")
  private val injectHandshakeInterval: FiniteDuration = 1.second
  private val giveUpSendAfter: FiniteDuration = 60.seconds
  private val shutdownFlushTimeout = 1.second

  private val remoteDispatcher = system.dispatchers.lookup(remoteSettings.Dispatcher)

  private val largeMessageDestinations =
    system.settings.config.getStringList("akka.remote.artery.large-message-destinations").asScala.foldLeft(WildcardTree[NotUsed]()) { (tree, entry) ⇒
      val segments = entry.split('/').tail
      tree.insert(segments.iterator, NotUsed)
    }
  private val largeMessageDestinationsEnabled = largeMessageDestinations.children.nonEmpty

  private def inboundChannel = s"aeron:udp?endpoint=${localAddress.address.host.get}:${localAddress.address.port.get}"
  private def outboundChannel(a: Address) = s"aeron:udp?endpoint=${a.host.get}:${a.port.get}"
  private val controlStreamId = 1
  private val ordinaryStreamId = 3
  private val largeStreamId = 4
  private val taskRunner = new TaskRunner(system, remoteSettings.IdleCpuLevel)

  private val restartTimeout: FiniteDuration = 5.seconds // FIXME config
  private val maxRestarts = 5 // FIXME config
  private val restartCounter = new RestartCounter(maxRestarts, restartTimeout)

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
    remoteAddress ⇒ new Association(this, materializer, remoteAddress, controlSubject, largeMessageDestinations,
      outboundEnvelopePool))

  def remoteSettings: RemoteSettings = provider.remoteSettings

  override def start(): Unit = {
    startMediaDriver()
    startAeron()
    topLevelFREvents.loFreq(Transport_AeronStarted, NoMetaData)
    startAeronErrorLog()
    topLevelFREvents.loFreq(Transport_AeronErrorLogStarted, NoMetaData)
    taskRunner.start()
    topLevelFREvents.loFreq(Transport_TaskRunnerStarted, NoMetaData)

    val port =
      if (remoteSettings.ArteryPort == 0) ArteryTransport.autoSelectPort(remoteSettings.ArteryHostname)
      else remoteSettings.ArteryPort

    // TODO: Configure materializer properly
    // TODO: Have a supervisor actor
    _localAddress = UniqueAddress(
      Address(ArteryTransport.ProtocolName, system.name, remoteSettings.ArteryHostname, port),
      AddressUidExtension(system).longAddressUid)
    _addresses = Set(_localAddress.address)

    // TODO: This probably needs to be a global value instead of an event as events might rotate out of the log
    topLevelFREvents.loFreq(Transport_UniqueAddressSet, _localAddress.toString().getBytes("US-ASCII"))

    val materializerSettings = ActorMaterializerSettings(
      remoteSettings.config.getConfig("akka.remote.artery.advanced.materializer"))
    materializer = ActorMaterializer.systemMaterializer(materializerSettings, "remote", system)

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
    if (remoteSettings.EmbeddedMediaDriver) {
      val driverContext = new MediaDriver.Context
      if (remoteSettings.AeronDirectoryName.nonEmpty)
        driverContext.aeronDirectoryName(remoteSettings.AeronDirectoryName)
      // FIXME settings from config
      driverContext.clientLivenessTimeoutNs(SECONDS.toNanos(20))
      driverContext.imageLivenessTimeoutNs(SECONDS.toNanos(20))
      driverContext.driverTimeoutMs(SECONDS.toNanos(20))

      val idleCpuLevel = remoteSettings.IdleCpuLevel
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
      mediaDriver = Some(driver)
    }
  }

  private def aeronDir: String = mediaDriver match {
    case Some(driver) ⇒ driver.aeronDirectoryName
    case None         ⇒ remoteSettings.AeronDirectoryName
  }

  private def stopMediaDriver(): Unit = {
    mediaDriver.foreach { driver ⇒
      // this is only for embedded media driver
      driver.close()
      try {
        // FIXME it should also be configurable to not delete dir
        IoUtil.delete(new File(driver.aeronDirectoryName), false)
      } catch {
        case NonFatal(e) ⇒
          log.warning(
            "Couldn't delete Aeron embedded media driver files in [{}] due to [{}]",
            driver.aeronDirectoryName, e.getMessage)
      }
    }
    Try(Runtime.getRuntime.removeShutdownHook(stopMediaDriverShutdownHook))
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
    if (largeMessageDestinationsEnabled) {
      runInboundLargeMessagesStream()
    }
  }

  private def runInboundControlStream(compression: InboundCompressions): Unit = {
    val (ctrl, completed) =
      if (remoteSettings.TestMode) {
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
                a.outboundCompression.applyActorRefCompressionTable(table)
                a.sendControl(ActorRefCompressionAdvertisementAck(localAddress, table.version))
                system.eventStream.publish(Events.ReceivedActorRefCompressionTable(from, table))
              case ActorRefCompressionAdvertisementAck(from, tableVersion) ⇒
                inboundCompressions.foreach(_.confirmActorRefCompressionAdvertisement(from.uid, tableVersion))
              case ClassManifestCompressionAdvertisement(from, table) ⇒
                log.debug("Incoming Class Manifest compression advertisement from [{}], table: [{}]", from, table)
                val a = association(from.address)
                a.outboundCompression.applyClassManifestCompressionTable(table)
                a.sendControl(ClassManifestCompressionAdvertisementAck(localAddress, table.version))
                system.eventStream.publish(Events.ReceivedClassManifestCompressionTable(from, table))
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
      if (remoteSettings.TestMode) {
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
      if (remoteSettings.TestMode) {
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
            streamName, maxRestarts, restartTimeout.toSeconds, cause.getMessage)
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
        system.systemActorOf(FlushOnShutdown.props(flushingPromise, shutdownFlushTimeout,
          this, allAssociations).withDispatcher(remoteSettings.Dispatcher), "remoteFlushOnShutdown")
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
      if (mediaDriver.isDefined) {
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
      import scala.collection.JavaConverters._
      import system.dispatcher
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

  override def completeHandshake(peer: UniqueAddress): Unit = {
    val a = associationRegistry.setUID(peer)
    a.completeHandshake(peer)
  }

  private def publishLifecycleEvent(event: RemotingLifecycleEvent): Unit =
    eventPublisher.notifyListeners(event)

  override def quarantine(remoteAddress: Address, uid: Option[Int]): Unit = {
    // FIXME change the method signature (old remoting) to include reason and use Long uid?
    association(remoteAddress).quarantine(reason = "", uid.map(_.toLong))
  }

  def outbound(outboundContext: OutboundContext, compression: OutboundCompressions): Sink[OutboundEnvelope, Future[Done]] = {
    Flow.fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(new OutboundHandshake(system, outboundContext, outboundEnvelopePool, handshakeTimeout,
        handshakeRetryInterval, injectHandshakeInterval))
      .via(encoder(compression))
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), ordinaryStreamId, aeron, taskRunner,
        envelopePool, giveUpSendAfter, createFlightRecorderEventSink()))(Keep.right)
  }

  def outboundLarge(outboundContext: OutboundContext, compression: OutboundCompressions): Sink[OutboundEnvelope, Future[Done]] = {
    Flow.fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(new OutboundHandshake(system, outboundContext, outboundEnvelopePool, handshakeTimeout,
        handshakeRetryInterval, injectHandshakeInterval))
      .via(createEncoder(largeEnvelopePool, compression))
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), largeStreamId, aeron, taskRunner,
        envelopePool, giveUpSendAfter, createFlightRecorderEventSink()))(Keep.right)
  }

  def outboundControl(outboundContext: OutboundContext, compression: OutboundCompressions): Sink[OutboundEnvelope, (OutboundControlIngress, Future[Done])] = {
    Flow.fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(new OutboundHandshake(system, outboundContext, outboundEnvelopePool, handshakeTimeout,
        handshakeRetryInterval, injectHandshakeInterval))
      .via(new SystemMessageDelivery(outboundContext, system.deadLetters, systemMessageResendInterval,
        remoteSettings.SysMsgBufferSize))
      .viaMat(new OutboundControlJunction(outboundContext, outboundEnvelopePool))(Keep.right)
      .via(encoder(compression))
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), controlStreamId, aeron, taskRunner,
        envelopePool, Duration.Inf, createFlightRecorderEventSink()))(Keep.both)

    // FIXME we can also add scrubbing stage that would collapse sys msg acks/nacks and remove duplicate Quarantine messages
  }

  def createEncoder(compression: OutboundCompressions, bufferPool: EnvelopeBufferPool): Flow[OutboundEnvelope, EnvelopeBuffer, NotUsed] =
    Flow.fromGraph(new Encoder(localAddress, system, compression, outboundEnvelopePool, bufferPool))

  private def createInboundCompressions(inboundContext: InboundContext): InboundCompressions =
    if (remoteSettings.ArteryCompressionSettings.enabled) new InboundCompressionsImpl(system, inboundContext)
    else NoInboundCompressions

  def createEncoder(pool: EnvelopeBufferPool, compression: OutboundCompressions): Flow[OutboundEnvelope, EnvelopeBuffer, NotUsed] =
    Flow.fromGraph(new Encoder(localAddress, system, compression, outboundEnvelopePool, pool))

  def encoder(compression: OutboundCompressions): Flow[OutboundEnvelope, EnvelopeBuffer, NotUsed] = createEncoder(envelopePool, compression)

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
    if (remoteSettings.FlightRecorderEnabled) {
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

