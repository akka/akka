/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal
import akka.Done
import akka.NotUsed
import akka.actor.Actor
import akka.actor.Props
import akka.actor._
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.remote.AddressUidExtension
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransport
import akka.remote.ThisActorSystemQuarantinedEvent
import akka.remote.UniqueAddress
import akka.remote.artery.Decoder.InboundCompressionAccess
import akka.remote.artery.Encoder.OutboundCompressionAccess
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.artery.compress.CompressionProtocol.CompressionMessage
import akka.remote.artery.compress._
import akka.remote.transport.ThrottlerTransportAdapter.Blackhole
import akka.remote.transport.ThrottlerTransportAdapter.SetThrottle
import akka.remote.transport.ThrottlerTransportAdapter.Unthrottled
import akka.stream.AbruptTerminationException
import akka.stream.ActorMaterializer
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.util.{ unused, OptionVal, WildcardIndex }

/**
 * INTERNAL API
 * Inbound API that is used by the stream operators.
 * Separate trait to facilitate testing without real transport.
 */
private[remote] trait InboundContext {

  /**
   * The local inbound address.
   */
  def localAddress: UniqueAddress

  /**
   * An inbound operator can send control message, e.g. a reply, to the origin
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
      lastUsedTimestamp = new AtomicLong(System.nanoTime()),
      controlIdleKillSwitch = OptionVal.None,
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
    val incarnation: Int,
    val uniqueRemoteAddressPromise: Promise[UniqueAddress],
    val lastUsedTimestamp: AtomicLong, // System.nanoTime timestamp
    val controlIdleKillSwitch: OptionVal[SharedKillSwitch],
    val quarantined: ImmutableLongMap[AssociationState.QuarantinedTimestamp]) {

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
        case Some(Success(peer)) =>
          uniqueRemoteAddressValueCache = Some(peer)
          uniqueRemoteAddressValueCache
        case _ => None
      }
    }
  }

  def newIncarnation(remoteAddressPromise: Promise[UniqueAddress]): AssociationState =
    new AssociationState(
      incarnation + 1,
      remoteAddressPromise,
      lastUsedTimestamp = new AtomicLong(System.nanoTime()),
      controlIdleKillSwitch,
      quarantined)

  def newQuarantined(): AssociationState =
    uniqueRemoteAddressPromise.future.value match {
      case Some(Success(a)) =>
        new AssociationState(
          incarnation,
          uniqueRemoteAddressPromise,
          lastUsedTimestamp = new AtomicLong(System.nanoTime()),
          controlIdleKillSwitch,
          quarantined = quarantined.updated(a.uid, QuarantinedTimestamp(System.nanoTime())))
      case _ => this
    }

  def isQuarantined(): Boolean = {
    uniqueRemoteAddressValue match {
      case Some(a) => isQuarantined(a.uid)
      case _       => false // handshake not completed yet
    }
  }

  def isQuarantined(uid: Long): Boolean = quarantined.contains(uid)

  def withControlIdleKillSwitch(killSwitch: OptionVal[SharedKillSwitch]): AssociationState =
    new AssociationState(
      incarnation,
      uniqueRemoteAddressPromise,
      lastUsedTimestamp,
      controlIdleKillSwitch = killSwitch,
      quarantined)

  override def toString(): String = {
    val a = uniqueRemoteAddressPromise.future.value match {
      case Some(Success(a)) => a
      case Some(Failure(e)) => s"Failure($e)"
      case None             => "unknown"
    }
    s"AssociationState($incarnation, $a)"
  }

}

/**
 * INTERNAL API
 * Outbound association API that is used by the stream operators.
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
   * An inbound operator can send control message, e.g. a HandshakeReq, to the remote
   * address of this association. It will be sent over the control sub-channel.
   */
  def sendControl(message: ControlMessage): Unit

  /**
   * @return `true` if any of the streams are active (not stopped due to idle)
   */
  def isOrdinaryMessageStreamActive(): Boolean

  /**
   * An outbound operator can listen to control messages
   * via this observer subject.
   */
  def controlSubject: ControlMessageSubject

  def settings: ArterySettings

}

/**
 * INTERNAL API
 */
private[remote] object FlushOnShutdown {
  def props(
      done: Promise[Done],
      timeout: FiniteDuration,
      inboundContext: InboundContext,
      associations: Set[Association]): Props = {
    require(associations.nonEmpty)
    Props(new FlushOnShutdown(done, timeout, inboundContext, associations))
  }

  case object Timeout
}

/**
 * INTERNAL API
 */
private[remote] class FlushOnShutdown(
    done: Promise[Done],
    timeout: FiniteDuration,
    @unused inboundContext: InboundContext,
    associations: Set[Association])
    extends Actor {

  var remaining = Map.empty[UniqueAddress, Int]

  val timeoutTask = context.system.scheduler.scheduleOnce(timeout, self, FlushOnShutdown.Timeout)(context.dispatcher)

  override def preStart(): Unit = {
    try {
      associations.foreach { a =>
        val acksExpected = a.sendTerminationHint(self)
        a.associationState.uniqueRemoteAddressValue() match {
          case Some(address) => remaining += address -> acksExpected
          case None          => // Ignore, handshake was not completed on this association
        }
      }
      if (remaining.valuesIterator.sum == 0) {
        done.trySuccess(Done)
        context.stop(self)
      }
    } catch {
      case NonFatal(e) =>
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
    case ActorSystemTerminatingAck(from) =>
      // Just treat unexpected acks as systems from which zero acks are expected
      val acksRemaining = remaining.getOrElse(from, 0)
      if (acksRemaining <= 1) {
        remaining -= from
      } else {
        remaining = remaining.updated(from, acksRemaining - 1)
      }

      if (remaining.isEmpty)
        context.stop(self)
    case FlushOnShutdown.Timeout =>
      context.stop(self)
  }
}

/**
 * INTERNAL API
 */
private[remote] abstract class ArteryTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider)
    extends RemoteTransport(_system, _provider)
    with InboundContext {
  import ArteryTransport._
  import FlightRecorderEvents._

  type LifeCycle

  // these vars are initialized once in the start method
  @volatile private[this] var _localAddress: UniqueAddress = _
  @volatile private[this] var _bindAddress: UniqueAddress = _
  @volatile private[this] var _addresses: Set[Address] = _
  @volatile protected var materializer: Materializer = _
  @volatile protected var controlMaterializer: Materializer = _
  @volatile private[this] var controlSubject: ControlMessageSubject = _
  @volatile private[this] var messageDispatcher: MessageDispatcher = _

  override val log: LoggingAdapter = Logging(system, getClass.getName)

  val (afrFileChannel, afrFile, flightRecorder) = initializeFlightRecorder() match {
    case None            => (None, None, None)
    case Some((c, f, r)) => (Some(c), Some(f), Some(r))
  }

  /**
   * Compression tables must be created once, such that inbound lane restarts don't cause dropping of the tables.
   * However are the InboundCompressions are owned by the Decoder operator, and any call into them must be looped through the Decoder!
   *
   * Use `inboundCompressionAccess` (provided by the materialized `Decoder`) to call into the compression infrastructure.
   */
  protected val _inboundCompressions = {
    if (settings.Advanced.Compression.Enabled) {
      val eventSink = createFlightRecorderEventSink(synchr = false)
      new InboundCompressionsImpl(system, this, settings.Advanced.Compression, eventSink)
    } else NoInboundCompressions
  }

  @volatile private[this] var _inboundCompressionAccess: OptionVal[InboundCompressionAccess] = OptionVal.None

  /** Only access compression tables via the CompressionAccess */
  def inboundCompressionAccess: OptionVal[InboundCompressionAccess] = _inboundCompressionAccess
  protected def setInboundCompressionAccess(a: InboundCompressionAccess): Unit =
    _inboundCompressionAccess = OptionVal(a)

  def bindAddress: UniqueAddress = _bindAddress
  override def localAddress: UniqueAddress = _localAddress
  override def defaultAddress: Address = if (_localAddress eq null) null else localAddress.address
  override def addresses: Set[Address] = _addresses
  override def localAddressForRemote(remote: Address): Address = defaultAddress

  protected val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

  // keyed by the streamId
  protected val streamMatValues = new AtomicReference(Map.empty[Int, InboundStreamMatValues[LifeCycle]])
  private[this] val hasBeenShutdown = new AtomicBoolean(false)

  private val testState = new SharedTestState

  protected val inboundLanes = settings.Advanced.InboundLanes

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
      .insert(Array("system", "cluster", "core", "daemon", "crossDcHeartbeatSender"), NotUsed)
      .insert(Array("system", "cluster", "heartbeatReceiver"), NotUsed)

  private val restartCounter =
    new RestartCounter(settings.Advanced.InboundMaxRestarts, settings.Advanced.InboundRestartTimeout)

  protected val envelopeBufferPool =
    new EnvelopeBufferPool(settings.Advanced.MaximumFrameSize, settings.Advanced.BufferPoolSize)
  protected val largeEnvelopeBufferPool =
    if (largeMessageChannelEnabled)
      new EnvelopeBufferPool(settings.Advanced.MaximumLargeFrameSize, settings.Advanced.LargeBufferPoolSize)
    else // not used
      new EnvelopeBufferPool(0, 2)

  private val inboundEnvelopePool = ReusableInboundEnvelope.createObjectPool(capacity = 16)
  // The outboundEnvelopePool is shared among all outbound associations
  private val outboundEnvelopePool = ReusableOutboundEnvelope.createObjectPool(
    capacity =
      settings.Advanced.OutboundMessageQueueSize * settings.Advanced.OutboundLanes * 3)

  /**
   * Thread-safe flight recorder for top level events.
   */
  val topLevelFlightRecorder: EventSink =
    createFlightRecorderEventSink(synchr = true)

  def createFlightRecorderEventSink(synchr: Boolean = false): EventSink = {
    flightRecorder match {
      case Some(f) =>
        val eventSink = f.createEventSink()
        if (synchr) new SynchronizedEventSink(eventSink)
        else eventSink
      case None =>
        IgnoreEventSink
    }
  }

  private val associationRegistry = new AssociationRegistry(
    remoteAddress =>
      new Association(
        this,
        materializer,
        controlMaterializer,
        remoteAddress,
        controlSubject,
        settings.LargeMessageDestinations,
        priorityMessageDestinations,
        outboundEnvelopePool))

  def remoteAddresses: Set[Address] = associationRegistry.allAssociations.map(_.remoteAddress)

  override def settings: ArterySettings = provider.remoteSettings.Artery

  override def start(): Unit = {
    if (system.settings.JvmShutdownHooks)
      Runtime.getRuntime.addShutdownHook(shutdownHook)

    startTransport()
    topLevelFlightRecorder.loFreq(Transport_Started, NoMetaData)

    val udp = settings.Transport == ArterySettings.AeronUpd
    val port =
      if (settings.Canonical.Port == 0) {
        if (settings.Bind.Port != 0) settings.Bind.Port // if bind port is set, use bind port instead of random
        else ArteryTransport.autoSelectPort(settings.Canonical.Hostname, udp)
      } else settings.Canonical.Port

    val bindPort = if (settings.Bind.Port == 0) {
      if (settings.Canonical.Port == 0) port // canonical and bind ports are zero. Use random port for both
      else ArteryTransport.autoSelectPort(settings.Bind.Hostname, udp)
    } else settings.Bind.Port

    _localAddress = UniqueAddress(
      Address(ArteryTransport.ProtocolName, system.name, settings.Canonical.Hostname, port),
      AddressUidExtension(system).longAddressUid)
    _addresses = Set(_localAddress.address)

    _bindAddress = UniqueAddress(
      Address(ArteryTransport.ProtocolName, system.name, settings.Bind.Hostname, bindPort),
      AddressUidExtension(system).longAddressUid)

    // TODO: This probably needs to be a global value instead of an event as events might rotate out of the log
    topLevelFlightRecorder.loFreq(Transport_UniqueAddressSet, _localAddress.toString())

    materializer = ActorMaterializer.systemMaterializer(settings.Advanced.MaterializerSettings, "remote", system)
    controlMaterializer =
      ActorMaterializer.systemMaterializer(settings.Advanced.MaterializerSettings, "remoteControl", system)

    messageDispatcher = new MessageDispatcher(system, provider)
    topLevelFlightRecorder.loFreq(Transport_MaterializerStarted, NoMetaData)

    runInboundStreams()
    topLevelFlightRecorder.loFreq(Transport_StartupFinished, NoMetaData)

    startRemoveQuarantinedAssociationTask()

    if (localAddress.address == bindAddress.address)
      log.info(
        "Remoting started with transport [Artery {}]; listening on address [{}] with UID [{}]",
        settings.Transport,
        bindAddress.address,
        bindAddress.uid)
    else {
      log.info(
        s"Remoting started with transport [Artery ${settings.Transport}]; listening on address [{}] and bound to [{}] with UID [{}]",
        localAddress.address,
        bindAddress.address,
        localAddress.uid)
    }

  }

  protected def startTransport(): Unit

  protected def runInboundStreams(): Unit

  private def startRemoveQuarantinedAssociationTask(): Unit = {
    val removeAfter = settings.Advanced.RemoveQuarantinedAssociationAfter
    val interval = removeAfter / 2
    system.scheduler.schedule(removeAfter, interval) {
      if (!isShutdown)
        associationRegistry.removeUnusedQuarantined(removeAfter)
    }(system.dispatcher)
  }

  // Select inbound lane based on destination to preserve message order,
  // Also include the uid of the sending system in the hash to spread
  // "hot" destinations, e.g. ActorSelection anchor.
  protected val inboundLanePartitioner: InboundEnvelope => Int = env => {
    env.recipient match {
      case OptionVal.Some(r) =>
        val a = r.path.uid
        val b = env.originUid
        val hashA = 23 + a
        val hash: Int = 23 * hashA + java.lang.Long.hashCode(b)
        math.abs(hash % inboundLanes)
      case OptionVal.None =>
        // the lane is set by the DuplicateHandshakeReq stage, otherwise 0
        env.lane
    }
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
            totalTimeout,
            localAddress)
        else
          log.debug("Shutting down [{}] via shutdownHook", localAddress)
        if (hasBeenShutdown.compareAndSet(false, true)) {
          Await.result(internalShutdown(), settings.Advanced.DriverTimeout + 3.seconds)
        }
      }
    }
  }

  protected def attachControlMessageObserver(ctrl: ControlMessageSubject): Unit = {
    controlSubject = ctrl
    controlSubject.attach(new ControlMessageObserver {
      override def notify(inboundEnvelope: InboundEnvelope): Unit = {
        try {
          inboundEnvelope.message match {
            case m: CompressionMessage =>
              import CompressionProtocol._
              m match {
                case ActorRefCompressionAdvertisement(from, table) =>
                  if (table.originUid == localAddress.uid) {
                    log.debug("Incoming ActorRef compression advertisement from [{}], table: [{}]", from, table)
                    val a = association(from.address)
                    // make sure uid is same for active association
                    if (a.associationState.uniqueRemoteAddressValue().contains(from)) {
                      import system.dispatcher
                      a.changeActorRefCompression(table).foreach { _ =>
                        a.sendControl(ActorRefCompressionAdvertisementAck(localAddress, table.version))
                        system.eventStream.publish(Events.ReceivedActorRefCompressionTable(from, table))
                      }
                    }
                  } else
                    log.debug(
                      "Discarding incoming ActorRef compression advertisement from [{}] that was " +
                      "prepared for another incarnation with uid [{}] than current uid [{}], table: [{}]",
                      from,
                      table.originUid,
                      localAddress.uid,
                      table)
                case ack: ActorRefCompressionAdvertisementAck =>
                  inboundCompressionAccess match {
                    case OptionVal.Some(access) => access.confirmActorRefCompressionAdvertisementAck(ack)
                    case _ =>
                      log.debug(
                        s"Received {} version: [{}] however no inbound compression access was present. " +
                        s"ACK will not take effect, however it will be redelivered and likely to apply then.",
                        Logging.simpleName(ack),
                        ack.tableVersion)
                  }

                case ClassManifestCompressionAdvertisement(from, table) =>
                  if (table.originUid == localAddress.uid) {
                    log.debug("Incoming Class Manifest compression advertisement from [{}], table: [{}]", from, table)
                    val a = association(from.address)
                    // make sure uid is same for active association
                    if (a.associationState.uniqueRemoteAddressValue().contains(from)) {
                      import system.dispatcher
                      a.changeClassManifestCompression(table).foreach { _ =>
                        a.sendControl(ClassManifestCompressionAdvertisementAck(localAddress, table.version))
                        system.eventStream.publish(Events.ReceivedClassManifestCompressionTable(from, table))
                      }
                    }
                  } else
                    log.debug(
                      "Discarding incoming Class Manifest compression advertisement from [{}] that was " +
                      "prepared for another incarnation with uid [{}] than current uid [{}], table: [{}]",
                      from,
                      table.originUid,
                      localAddress.uid,
                      table)
                case ack: ClassManifestCompressionAdvertisementAck =>
                  inboundCompressionAccess match {
                    case OptionVal.Some(access) => access.confirmClassManifestCompressionAdvertisementAck(ack)
                    case _ =>
                      log.debug(
                        s"Received {} version: [{}] however no inbound compression access was present. " +
                        s"ACK will not take effect, however it will be redelivered and likely to apply then.",
                        Logging.simpleName(ack),
                        ack.tableVersion)
                  }
              }

            case Quarantined(from, to) if to == localAddress =>
              // Don't quarantine the other system here, since that will result cluster member removal
              // and can result in forming two separate clusters (cluster split).
              // Instead, the downing strategy should act on ThisActorSystemQuarantinedEvent, e.g.
              // use it as a STONITH signal.
              val lifecycleEvent = ThisActorSystemQuarantinedEvent(localAddress.address, from.address)
              system.eventStream.publish(lifecycleEvent)

            case _ => // not interesting
          }
        } catch {
          case ShuttingDown => // silence it
        }
      }

      override def controlSubjectCompleted(signal: Try[Done]): Unit = ()
    })

  }

  protected def attachInboundStreamRestart(
      streamName: String,
      streamCompleted: Future[Done],
      restart: () => Unit): Unit = {
    implicit val ec = materializer.executionContext
    streamCompleted.failed.foreach {
      case ShutdownSignal      => // shutdown as expected
      case _: AeronTerminated  => // shutdown already in progress
      case cause if isShutdown =>
        // don't restart after shutdown, but log some details so we notice
        log.error(cause, s"{} failed after shutdown. {}", streamName, cause.getMessage)
      case _: AbruptTerminationException => // ActorSystem shutdown
      case cause =>
        if (restartCounter.restart()) {
          log.error(cause, "{} failed. Restarting it. {}", streamName, cause.getMessage)
          topLevelFlightRecorder.loFreq(Transport_RestartInbound, s"$localAddress - $streamName")
          restart()
        } else {
          log.error(
            cause,
            "{} failed and restarted {} times within {} seconds. Terminating system. {}",
            streamName,
            settings.Advanced.InboundMaxRestarts,
            settings.Advanced.InboundRestartTimeout.toSeconds,
            cause.getMessage)
          system.terminate()
        }
    }
  }

  override def shutdown(): Future[Done] = {
    if (hasBeenShutdown.compareAndSet(false, true)) {
      log.debug("Shutting down [{}]", localAddress)
      if (system.settings.JvmShutdownHooks)
        Try(Runtime.getRuntime.removeShutdownHook(shutdownHook)) // may throw if shutdown already in progress
      val allAssociations = associationRegistry.allAssociations
      val flushing: Future[Done] =
        if (allAssociations.isEmpty) Future.successful(Done)
        else {
          val flushingPromise = Promise[Done]()
          system.systemActorOf(
            FlushOnShutdown.props(flushingPromise, settings.Advanced.ShutdownFlushTimeout, this, allAssociations),
            "remoteFlushOnShutdown")
          flushingPromise.future
        }
      implicit val ec = system.dispatcher
      flushing.recover { case _ => Done }.flatMap(_ => internalShutdown())
    } else {
      Future.successful(Done)
    }
  }

  private def internalShutdown(): Future[Done] = {
    import system.dispatcher

    killSwitch.abort(ShutdownSignal)
    topLevelFlightRecorder.loFreq(Transport_KillSwitchPulled, NoMetaData)
    for {
      _ <- streamsCompleted.recover { case _    => Done }
      _ <- shutdownTransport().recover { case _ => Done }
    } yield {
      // no need to explicitly shut down the contained access since it's lifecycle is bound to the Decoder
      _inboundCompressionAccess = OptionVal.None

      topLevelFlightRecorder.loFreq(Transport_FlightRecorderClose, NoMetaData)
      flightRecorder.foreach(_.close())
      afrFileChannel.foreach(_.force(true))
      afrFileChannel.foreach(_.close())
      Done
    }
  }

  protected def shutdownTransport(): Future[Done]

  @tailrec final protected def updateStreamMatValues(streamId: Int, values: InboundStreamMatValues[LifeCycle]): Unit = {
    val prev = streamMatValues.get()
    if (!streamMatValues.compareAndSet(prev, prev + (streamId -> values))) {
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
      _ <- Future.traverse(associationRegistry.allAssociations)(_.streamsCompleted)
      _ <- Future.sequence(streamMatValues.get().valuesIterator.map {
        case InboundStreamMatValues(_, done) => done
      })
    } yield Done
  }

  private[remote] def isShutdown: Boolean = hasBeenShutdown.get()

  override def managementCommand(cmd: Any): Future[Boolean] = {
    cmd match {
      case SetThrottle(address, direction, Blackhole) =>
        testState.blackhole(localAddress.address, address, direction)
      case SetThrottle(address, direction, Unthrottled) =>
        testState.passThrough(localAddress.address, address, direction)
      case TestManagementCommands.FailInboundStreamOnce(ex) =>
        testState.failInboundStreamOnce(ex)
    }
    Future.successful(true)
  }

  // InboundContext
  override def sendControl(to: Address, message: ControlMessage) =
    try {
      association(to).sendControl(message)
    } catch {
      case ShuttingDown => // silence it
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
      case ShuttingDown => // silence it
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
      associationRegistry.setUID(peer).completeHandshake(peer)
    } catch {
      case ShuttingDown => Future.successful(Done) // silence it
    }
  }

  override def quarantine(remoteAddress: Address, uid: Option[Long], reason: String): Unit = {
    quarantine(remoteAddress, uid, reason, harmless = false)
  }

  def quarantine(remoteAddress: Address, uid: Option[Long], reason: String, harmless: Boolean): Unit = {
    try {
      association(remoteAddress).quarantine(reason, uid, harmless)
    } catch {
      case ShuttingDown => // silence it
    }
  }

  def outboundLarge(outboundContext: OutboundContext): Sink[OutboundEnvelope, Future[Done]] =
    createOutboundSink(LargeStreamId, outboundContext, largeEnvelopeBufferPool).mapMaterializedValue {
      case (_, d) => d
    }

  def outbound(outboundContext: OutboundContext): Sink[OutboundEnvelope, (OutboundCompressionAccess, Future[Done])] =
    createOutboundSink(OrdinaryStreamId, outboundContext, envelopeBufferPool)

  private def createOutboundSink(
      streamId: Int,
      outboundContext: OutboundContext,
      bufferPool: EnvelopeBufferPool): Sink[OutboundEnvelope, (OutboundCompressionAccess, Future[Done])] = {

    outboundLane(outboundContext, bufferPool, streamId).toMat(
      outboundTransportSink(outboundContext, streamId, bufferPool))(Keep.both)
  }

  def outboundTransportSink(outboundContext: OutboundContext): Sink[EnvelopeBuffer, Future[Done]] =
    outboundTransportSink(outboundContext, OrdinaryStreamId, envelopeBufferPool)

  protected def outboundTransportSink(
      outboundContext: OutboundContext,
      streamId: Int,
      bufferPool: EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]]

  def outboundLane(
      outboundContext: OutboundContext): Flow[OutboundEnvelope, EnvelopeBuffer, OutboundCompressionAccess] =
    outboundLane(outboundContext, envelopeBufferPool, OrdinaryStreamId)

  private def outboundLane(
      outboundContext: OutboundContext,
      bufferPool: EnvelopeBufferPool,
      streamId: Int): Flow[OutboundEnvelope, EnvelopeBuffer, OutboundCompressionAccess] = {

    Flow
      .fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(
        new OutboundHandshake(
          system,
          outboundContext,
          outboundEnvelopePool,
          settings.Advanced.HandshakeTimeout,
          settings.Advanced.HandshakeRetryInterval,
          settings.Advanced.InjectHandshakeInterval,
          Duration.Undefined))
      .viaMat(createEncoder(bufferPool, streamId))(Keep.right)
  }

  def outboundControl(
      outboundContext: OutboundContext): Sink[OutboundEnvelope, (OutboundControlIngress, Future[Done])] = {
    val livenessProbeInterval =
      (settings.Advanced.QuarantineIdleOutboundAfter / 10).max(settings.Advanced.HandshakeRetryInterval)
    Flow
      .fromGraph(killSwitch.flow[OutboundEnvelope])
      .via(
        new OutboundHandshake(
          system,
          outboundContext,
          outboundEnvelopePool,
          settings.Advanced.HandshakeTimeout,
          settings.Advanced.HandshakeRetryInterval,
          settings.Advanced.InjectHandshakeInterval,
          livenessProbeInterval))
      .via(
        new SystemMessageDelivery(
          outboundContext,
          system.deadLetters,
          settings.Advanced.SystemMessageResendInterval,
          settings.Advanced.SysMsgBufferSize))
      // note that System messages must not be dropped before the SystemMessageDelivery stage
      .via(outboundTestFlow(outboundContext))
      .viaMat(new OutboundControlJunction(outboundContext, outboundEnvelopePool))(Keep.right)
      .via(createEncoder(envelopeBufferPool, ControlStreamId))
      .toMat(outboundTransportSink(outboundContext, ControlStreamId, envelopeBufferPool))(Keep.both)

    // TODO we can also add scrubbing stage that would collapse sys msg acks/nacks and remove duplicate Quarantine messages
  }

  def createEncoder(
      pool: EnvelopeBufferPool,
      streamId: Int): Flow[OutboundEnvelope, EnvelopeBuffer, OutboundCompressionAccess] =
    Flow.fromGraph(
      new Encoder(localAddress, system, outboundEnvelopePool, pool, streamId, settings.LogSend, settings.Version))

  def createDecoder(
      settings: ArterySettings,
      compressions: InboundCompressions): Flow[EnvelopeBuffer, InboundEnvelope, InboundCompressionAccess] =
    Flow.fromGraph(new Decoder(this, system, localAddress, settings, compressions, inboundEnvelopePool))

  def createDeserializer(bufferPool: EnvelopeBufferPool): Flow[InboundEnvelope, InboundEnvelope, NotUsed] =
    Flow.fromGraph(new Deserializer(this, system, bufferPool))

  val messageDispatcherSink: Sink[InboundEnvelope, Future[Done]] = Sink.foreach[InboundEnvelope] { m =>
    messageDispatcher.dispatch(m)
    m match {
      case r: ReusableInboundEnvelope => inboundEnvelopePool.release(r)
      case _                          =>
    }
  }

  // Checks for termination hint messages and sends an ACK for those (not processing them further)
  // Purpose of this stage is flushing, the sender can wait for the ACKs up to try flushing
  // pending messages.
  def terminationHintReplier(inControlStream: Boolean): Flow[InboundEnvelope, InboundEnvelope, NotUsed] = {
    Flow[InboundEnvelope].filter { envelope =>
      envelope.message match {
        case ActorSystemTerminating(from) =>
          envelope.sender match {
            case OptionVal.Some(snd) =>
              snd.tell(ActorSystemTerminatingAck(localAddress), ActorRef.noSender)
              if (inControlStream)
                system.scheduler.scheduleOnce(settings.Advanced.ShutdownFlushTimeout) {
                  if (!isShutdown)
                    quarantine(from.address, Some(from.uid), "ActorSystem terminated", harmless = true)
                }(materializer.executionContext)
            case OptionVal.None =>
              log.error("Expected sender for ActorSystemTerminating message from [{}]", from)
          }
          false
        case _ => true
      }
    }
  }

  def inboundSink(bufferPool: EnvelopeBufferPool): Sink[InboundEnvelope, Future[Done]] =
    Flow[InboundEnvelope]
      .via(createDeserializer(bufferPool))
      .via(if (settings.Advanced.TestMode) new InboundTestStage(this, testState) else Flow[InboundEnvelope])
      .via(terminationHintReplier(inControlStream = false))
      .via(new InboundHandshake(this, inControlStream = false))
      .via(new InboundQuarantineCheck(this))
      .toMat(messageDispatcherSink)(Keep.right)

  def inboundFlow(
      settings: ArterySettings,
      compressions: InboundCompressions): Flow[EnvelopeBuffer, InboundEnvelope, InboundCompressionAccess] = {
    Flow[EnvelopeBuffer].via(killSwitch.flow).viaMat(createDecoder(settings, compressions))(Keep.right)
  }

  // large messages flow does not use compressions, since the message size dominates the size anyway
  def inboundLargeFlow(settings: ArterySettings): Flow[EnvelopeBuffer, InboundEnvelope, Any] =
    inboundFlow(settings, NoInboundCompressions)

  def inboundControlSink: Sink[InboundEnvelope, (ControlMessageSubject, Future[Done])] = {
    Flow[InboundEnvelope]
      .via(createDeserializer(envelopeBufferPool))
      .via(if (settings.Advanced.TestMode) new InboundTestStage(this, testState) else Flow[InboundEnvelope])
      .via(terminationHintReplier(inControlStream = true))
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
    if (settings.Advanced.TestMode) Flow.fromGraph(new OutboundTestStage(outboundContext, testState))
    else Flow[OutboundEnvelope]

  /** INTERNAL API: for testing only. */
  private[remote] def triggerCompressionAdvertisements(actorRef: Boolean, manifest: Boolean) = {
    inboundCompressionAccess match {
      case OptionVal.Some(c) if actorRef || manifest =>
        log.info("Triggering compression table advertisement for {}", c)
        if (actorRef) c.runNextActorRefAdvertisement()
        if (manifest) c.runNextClassManifestAdvertisement()
      case _ =>
    }
  }

}

/**
 * INTERNAL API
 */
private[remote] object ArteryTransport {

  val ProtocolName = "akka"

  // Note that the used version of the header format for outbound messages is defined in
  // `ArterySettings.Version` because that may depend on configuration settings.
  // This is the highest supported version on receiving (decoding) side.
  // ArterySettings.Version can be lower than this HighestVersion to support rolling upgrades.
  val HighestVersion: Byte = 0

  class AeronTerminated(e: Throwable) extends RuntimeException(e)

  object ShutdownSignal extends RuntimeException with NoStackTrace

  // thrown when the transport is shutting down and something triggers a new association
  object ShuttingDown extends RuntimeException with NoStackTrace

  final case class InboundStreamMatValues[LifeCycle](lifeCycle: LifeCycle, completed: Future[Done])

  def autoSelectPort(hostname: String, udp: Boolean): Int = {
    if (udp) {
      val socket = DatagramChannel.open().socket()
      socket.bind(new InetSocketAddress(hostname, 0))
      val port = socket.getLocalPort
      socket.close()
      port
    } else {
      val socket = ServerSocketChannel.open().socket()
      socket.bind(new InetSocketAddress(hostname, 0))
      val port = socket.getLocalPort
      socket.close()
      port
    }
  }

  val ControlStreamId = 1
  val OrdinaryStreamId = 2
  val LargeStreamId = 3

  def streamName(streamId: Int): String =
    streamId match {
      case ControlStreamId => "control"
      case LargeStreamId   => "large message"
      case _               => "message"
    }

}
