/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.function.{ Function ⇒ JFunction }

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.Done
import akka.NotUsed
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.remote.AddressUidExtension
import akka.remote.EndpointManager.Send
import akka.remote.EventPublisher
import akka.remote.MessageSerializer
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransport
import akka.remote.RemotingLifecycleEvent
import akka.remote.SeqNo
import akka.remote.ThisActorSystemQuarantinedEvent
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.transport.AkkaPduCodec
import akka.remote.transport.AkkaPduProtobufCodec
import akka.serialization.Serialization
import akka.stream.AbruptTerminationException
import akka.stream.ActorMaterializer
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.util.ByteStringBuilder
import akka.util.Helpers.ConfigOps
import akka.util.Helpers.Requiring
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
import java.nio.channels.DatagramChannel

import akka.remote.artery.OutboundControlJunction.OutboundControlIngress

/**
 * INTERNAL API
 */
private[akka] final case class InboundEnvelope(
  recipient: InternalActorRef,
  recipientAddress: Address,
  message: AnyRef,
  senderOption: Option[ActorRef],
  originAddress: UniqueAddress)

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

}

/**
 * INTERNAL API
 */
private[akka] object AssociationState {
  def apply(): AssociationState =
    new AssociationState(incarnation = 1, uniqueRemoteAddressPromise = Promise(), quarantined = Set.empty)
}

/**
 * INTERNAL API
 */
private[akka] final class AssociationState(
  val incarnation: Int,
  val uniqueRemoteAddressPromise: Promise[UniqueAddress],
  val quarantined: Set[Long]) {

  /**
   * Full outbound address with UID for this association.
   * Completed when by the handshake.
   */
  def uniqueRemoteAddress: Future[UniqueAddress] = uniqueRemoteAddressPromise.future

  def uniqueRemoteAddressValue(): Option[Try[UniqueAddress]] = {
    // FIXME we should cache access to uniqueRemoteAddress.value (avoid allocations), used in many places
    uniqueRemoteAddress.value
  }

  def newIncarnation(remoteAddressPromise: Promise[UniqueAddress]): AssociationState =
    new AssociationState(incarnation + 1, remoteAddressPromise, quarantined)

  def newQuarantined(): AssociationState =
    uniqueRemoteAddressPromise.future.value match {
      case Some(Success(a)) ⇒
        new AssociationState(incarnation, uniqueRemoteAddressPromise, quarantined = quarantined + a.uid)
      case _ ⇒ this
    }

  def isQuarantined(): Boolean = {
    uniqueRemoteAddressValue match {
      case Some(Success(a)) ⇒ isQuarantined(a.uid)
      case _                ⇒ false // handshake not completed yet
    }
  }

  def isQuarantined(uid: Long): Boolean = {
    // FIXME does this mean boxing (allocation) because of Set[Long]? Use specialized Set. LongMap?
    quarantined(uid)
  }

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

  def completeHandshake(peer: UniqueAddress): Unit

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

  // FIXME we should be able to Send without a recipient ActorRef
  def dummyRecipient: RemoteActorRef
}

/**
 * INTERNAL API
 */
private[remote] class ArteryTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider)
  extends RemoteTransport(_system, _provider) with InboundContext {
  import provider.remoteSettings

  // these vars are initialized once in the start method
  @volatile private[this] var _localAddress: UniqueAddress = _
  override def localAddress: UniqueAddress = _localAddress
  @volatile private[this] var materializer: Materializer = _
  @volatile private[this] var controlSubject: ControlMessageSubject = _
  @volatile private[this] var messageDispatcher: MessageDispatcher = _
  @volatile private[this] var driver: MediaDriver = _
  @volatile private[this] var aeron: Aeron = _

  override def defaultAddress: Address = localAddress.address
  override def addresses: Set[Address] = Set(defaultAddress)
  override def localAddressForRemote(remote: Address): Address = defaultAddress
  override val log: LoggingAdapter = Logging(system, getClass.getName)
  private val eventPublisher = new EventPublisher(system, log, remoteSettings.RemoteLifecycleEventsLogLevel)

  private val codec: AkkaPduCodec = AkkaPduProtobufCodec
  private val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")
  @volatile private[this] var _shutdown = false

  // FIXME config
  private val systemMessageResendInterval: FiniteDuration = 1.second
  private val handshakeRetryInterval: FiniteDuration = 1.second
  private val handshakeTimeout: FiniteDuration =
    system.settings.config.getMillisDuration("akka.remote.handshake-timeout").requiring(_ > Duration.Zero,
      "handshake-timeout must be > 0")

  private def inboundChannel = s"aeron:udp?endpoint=${localAddress.address.host.get}:${localAddress.address.port.get}"
  private def outboundChannel(a: Address) = s"aeron:udp?endpoint=${a.host.get}:${a.port.get}"
  private val controlStreamId = 1
  private val ordinaryStreamId = 3
  private val taskRunner = new TaskRunner(system)

  // FIXME: This does locking on putIfAbsent, we need something smarter
  private[this] val associations = new ConcurrentHashMap[Address, Association]()

  private val restartTimeout: FiniteDuration = 5.seconds // FIXME config
  private val maxRestarts = 5 // FIXME config
  private val restartCounter = new RestartCounter(maxRestarts, restartTimeout)

  val envelopePool = new EnvelopeBufferPool(ArteryTransport.MaximumFrameSize, ArteryTransport.MaximumPooledBuffers)
  // FIXME: Compression table must be owned by each channel instead
  // of having a global one
  val compression = new Compression(system)

  override def start(): Unit = {
    startMediaDriver()
    startAeron()
    taskRunner.start()

    val port =
      if (remoteSettings.ArteryPort == 0) ArteryTransport.autoSelectPort(remoteSettings.ArteryHostname)
      else remoteSettings.ArteryPort

    // TODO: Configure materializer properly
    // TODO: Have a supervisor actor
    _localAddress = UniqueAddress(
      Address("artery", system.name, remoteSettings.ArteryHostname, port),
      AddressUidExtension(system).addressUid)
    materializer = ActorMaterializer()(system)

    messageDispatcher = new MessageDispatcher(system, provider)

    runInboundStreams()

    log.info("Remoting started; listening on address: {}", defaultAddress)
  }

  private def startMediaDriver(): Unit = {
    // TODO also support external media driver
    val driverContext = new MediaDriver.Context
    // FIXME settings from config
    driverContext.clientLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.imageLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.driverTimeoutMs(SECONDS.toNanos(10))
    driver = MediaDriver.launchEmbedded(driverContext)
  }

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

    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    aeron = Aeron.connect(ctx)
  }

  private def runInboundStreams(): Unit = {
    runInboundControlStream()
    runInboundOrdinaryMessagesStream()
  }

  private def runInboundControlStream(): Unit = {
    val (c, completed) = Source.fromGraph(new AeronSource(inboundChannel, controlStreamId, aeron, taskRunner, envelopePool))
      .async // FIXME measure
      .viaMat(inboundControlFlow)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .run()(materializer)
    controlSubject = c

    controlSubject.attach(new ControlMessageObserver {
      override def notify(inboundEnvelope: InboundEnvelope): Unit = {
        inboundEnvelope.message match {
          case Quarantined(from, to) if to == localAddress ⇒
            val lifecycleEvent = ThisActorSystemQuarantinedEvent(localAddress.address, from.address)
            publishLifecycleEvent(lifecycleEvent)
            // quarantine the other system from here
            association(from.address).quarantine(lifecycleEvent.toString, Some(from.uid))
          case _ ⇒ // not interesting
        }
      }
    })

    // ordinary messages stream
    controlSubject.attach(new ControlMessageObserver {
      override def notify(inboundEnvelope: InboundEnvelope): Unit = {
        inboundEnvelope.message match {
          case Quarantined(from, to) if to == localAddress ⇒
            val lifecycleEvent = ThisActorSystemQuarantinedEvent(localAddress.address, from.address)
            publishLifecycleEvent(lifecycleEvent)
            // quarantine the other system from here
            association(from.address).quarantine(lifecycleEvent.toString, Some(from.uid))
          case _ ⇒ // not interesting
        }
      }
    })

    attachStreamRestart("Inbound control stream", completed, () ⇒ runInboundControlStream())
  }

  private def runInboundOrdinaryMessagesStream(): Unit = {
    val completed = Source.fromGraph(new AeronSource(inboundChannel, ordinaryStreamId, aeron, taskRunner, envelopePool))
      .async // FIXME measure
      .via(inboundFlow)
      .runWith(Sink.ignore)(materializer)

    attachStreamRestart("Inbound message stream", completed, () ⇒ runInboundOrdinaryMessagesStream())
  }

  private def attachStreamRestart(streamName: String, streamCompleted: Future[Done], restart: () ⇒ Unit): Unit = {
    implicit val ec = materializer.executionContext
    streamCompleted.onFailure {
      case _: AbruptTerminationException ⇒ // ActorSystem shutdown
      case cause ⇒
        if (!isShutdown)
          if (restartCounter.restart()) {
            log.error(cause, "{} failed. Restarting it.", streamName)
            restart()
          } else {
            log.error(cause, "{} failed and restarted {} times within {} seconds. Terminating system.",
              streamName, maxRestarts, restartTimeout.toSeconds)
            system.terminate()
          }
    }
  }

  override def shutdown(): Future[Done] = {
    _shutdown = true
    killSwitch.shutdown()
    if (taskRunner != null) taskRunner.stop()
    if (aeron != null) aeron.close()
    if (driver != null) {
      driver.close()
      // FIXME only delete files for embedded media driver, and it should also be configurable
      IoUtil.delete(new File(driver.aeronDirectoryName), true)
    }
    Future.successful(Done)
  }

  private[remote] def isShutdown(): Boolean = _shutdown

  // InboundContext
  override def sendControl(to: Address, message: ControlMessage) =
    association(to).outboundControlIngress.sendControlMessage(message)

  override def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    val cached = recipient.cachedAssociation
    val remoteAddress = recipient.path.address

    val a =
      if (cached ne null) cached
      else association(remoteAddress)

    a.send(message, senderOption, recipient)
  }

  override def association(remoteAddress: Address): Association = {
    val current = associations.get(remoteAddress)
    if (current ne null) current
    else {
      associations.computeIfAbsent(remoteAddress, new JFunction[Address, Association] {
        override def apply(remoteAddress: Address): Association = {
          val newAssociation = new Association(ArteryTransport.this, materializer, remoteAddress, controlSubject)
          newAssociation.associate() // This is a bit costly for this blocking method :(
          newAssociation
        }
      })
    }
  }

  private def publishLifecycleEvent(event: RemotingLifecycleEvent): Unit =
    eventPublisher.notifyListeners(event)

  override def quarantine(remoteAddress: Address, uid: Option[Int]): Unit =
    association(remoteAddress).quarantine(reason = "", uid) // FIXME change the method signature (old remoting) to include reason?

  def outbound(outboundContext: OutboundContext): Sink[Send, Future[Done]] = {
    Flow.fromGraph(killSwitch.flow[Send])
      .via(new OutboundHandshake(outboundContext, handshakeTimeout, handshakeRetryInterval))
      .via(encoder)
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), ordinaryStreamId, aeron, taskRunner, envelopePool))(Keep.right)
  }

  def outboundControl(outboundContext: OutboundContext): Sink[Send, (OutboundControlIngress, Future[Done])] = {
    Flow.fromGraph(killSwitch.flow[Send])
      .via(new OutboundHandshake(outboundContext, handshakeTimeout, handshakeRetryInterval))
      .via(new SystemMessageDelivery(outboundContext, systemMessageResendInterval, remoteSettings.SysMsgBufferSize))
      .viaMat(new OutboundControlJunction(outboundContext))(Keep.right)
      .via(encoder)
      .toMat(new AeronSink(outboundChannel(outboundContext.remoteAddress), controlStreamId, aeron, taskRunner, envelopePool))(Keep.both)

    // FIXME we can also add scrubbing stage that would collapse sys msg acks/nacks and remove duplicate Quarantine messages
  }

  // FIXME hack until real envelopes, encoding originAddress in sender :)
  private val dummySender = system.systemActorOf(Props.empty, "dummy")

  val encoder: Flow[Send, EnvelopeBuffer, NotUsed] =
    Flow.fromGraph(new Encoder(this, compression))

  val messageDispatcherSink: Sink[InboundEnvelope, Future[Done]] = Sink.foreach[InboundEnvelope] { m ⇒
    messageDispatcher.dispatch(m.recipient, m.recipientAddress, m.message, m.senderOption)
  }

  val decoder = Flow.fromGraph(new Decoder(this, compression))

  val inboundFlow: Flow[EnvelopeBuffer, ByteString, NotUsed] = {
    Flow.fromSinkAndSource(
      decoder
        .via(new InboundHandshake(this, inControlStream = false))
        .via(new InboundQuarantineCheck(this))
        .to(messageDispatcherSink),
      Source.maybe[ByteString].via(killSwitch.flow))
  }

  val inboundControlFlow: Flow[EnvelopeBuffer, ByteString, ControlMessageSubject] = {
    Flow.fromSinkAndSourceMat(
      decoder
        .via(new InboundHandshake(this, inControlStream = true))
        .via(new InboundQuarantineCheck(this))
        .viaMat(new InboundControlJunction)(Keep.right)
        .via(new SystemMessageAcker(this))
        .to(messageDispatcherSink),
      Source.maybe[ByteString].via(killSwitch.flow))((a, b) ⇒ a)
  }

}

/**
 * INTERNAL API
 */
private[remote] object ArteryTransport {

  val Version = 0
  val MaximumFrameSize = 1024 * 1024
  val MaximumPooledBuffers = 256

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

