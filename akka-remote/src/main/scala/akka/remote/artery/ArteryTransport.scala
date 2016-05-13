/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.function.{ Function ⇒ JFunction }
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.Done
import akka.NotUsed
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.InternalActorRef
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.remote.AddressUidExtension
import akka.remote.EndpointManager.Send
import akka.remote.MessageSerializer
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransport
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.transport.AkkaPduCodec
import akka.remote.transport.AkkaPduProtobufCodec
import akka.serialization.Serialization
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
import io.aeron.Aeron
import io.aeron.AvailableImageHandler
import io.aeron.Image
import io.aeron.UnavailableImageHandler
import io.aeron.driver.MediaDriver
import io.aeron.exceptions.ConductorServiceTimeoutException
import org.agrona.ErrorHandler
import org.agrona.IoUtil
import java.io.File
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress

/**
 * INTERNAL API
 */
private[akka] final case class InboundEnvelope(
  recipient: InternalActorRef,
  recipientAddress: Address,
  message: AnyRef,
  senderOption: Option[ActorRef])

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
   * address with this method.
   */
  def sendControl(to: Address, message: ControlMessage): Unit

  /**
   * Lookup the outbound association for a given address.
   */
  def association(remoteAddress: Address): OutboundContext
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

  /**
   * Full outbound address with UID for this association.
   * Completed when by the handshake.
   */
  def uniqueRemoteAddress: Future[UniqueAddress]

  /**
   * Set the outbound address with UID when the
   * handshake is completed.
   */
  def completeRemoteAddress(a: UniqueAddress): Unit

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

  override val log: LoggingAdapter = Logging(system.eventStream, getClass.getName)
  override def defaultAddress: Address = localAddress.address
  override def addresses: Set[Address] = Set(defaultAddress)
  override def localAddressForRemote(remote: Address): Address = defaultAddress

  private val codec: AkkaPduCodec = AkkaPduProtobufCodec
  private val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

  // FIXME config
  private val systemMessageResendInterval: FiniteDuration = 1.second
  private val handshakeTimeout: FiniteDuration = 10.seconds

  // TODO support port 0
  private def inboundChannel = s"aeron:udp?endpoint=${localAddress.address.host.get}:${localAddress.address.port.get}"
  private def outboundChannel(a: Address) = s"aeron:udp?endpoint=${a.host.get}:${a.port.get}"
  private val controlStreamId = 1
  private val ordinaryStreamId = 3
  private val taskRunner = new TaskRunner(system)

  // FIXME: This does locking on putIfAbsent, we need something smarter
  private[this] val associations = new ConcurrentHashMap[Address, Association]()

  override def start(): Unit = {
    startMediaDriver()
    startAeron()
    taskRunner.start()

    // TODO: Configure materializer properly
    // TODO: Have a supervisor actor
    _localAddress = UniqueAddress(
      Address("akka.artery", system.name, remoteSettings.ArteryHostname, remoteSettings.ArteryPort),
      AddressUidExtension(system).addressUid)
    materializer = ActorMaterializer()(system)

    messageDispatcher = new MessageDispatcher(system, provider)

    runInboundFlows()
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

  private def runInboundFlows(): Unit = {
    controlSubject = Source.fromGraph(new AeronSource(inboundChannel, controlStreamId, aeron, taskRunner))
      .async // FIXME measure
      .map(ByteString.apply) // TODO we should use ByteString all the way
      .viaMat(inboundControlFlow)(Keep.right)
      .to(Sink.ignore)
      .run()(materializer)

    Source.fromGraph(new AeronSource(inboundChannel, ordinaryStreamId, aeron, taskRunner))
      .async // FIXME measure
      .map(ByteString.apply) // TODO we should use ByteString all the way
      .via(inboundFlow)
      .runWith(Sink.ignore)(materializer)
  }

  override def shutdown(): Future[Done] = {
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

  override def quarantine(remoteAddress: Address, uid: Option[Int]): Unit = {
    ???
  }

  def outbound(outboundContext: OutboundContext): Sink[Send, Any] = {
    Flow.fromGraph(killSwitch.flow[Send])
      .via(new OutboundHandshake(outboundContext, handshakeTimeout))
      .via(encoder)
      .map(_.toArray) // TODO we should use ByteString all the way
      .to(new AeronSink(outboundChannel(outboundContext.remoteAddress), ordinaryStreamId, aeron, taskRunner))
  }

  def outboundControl(outboundContext: OutboundContext): Sink[Send, OutboundControlIngress] = {
    Flow.fromGraph(killSwitch.flow[Send])
      .via(new OutboundHandshake(outboundContext, handshakeTimeout))
      .via(new SystemMessageDelivery(outboundContext, systemMessageResendInterval))
      .viaMat(new OutboundControlJunction(outboundContext))(Keep.right)
      .via(encoder)
      .map(_.toArray) // TODO we should use ByteString all the way
      .to(new AeronSink(outboundChannel(outboundContext.remoteAddress), controlStreamId, aeron, taskRunner))
  }

  // TODO: Try out parallelized serialization (mapAsync) for performance
  val encoder: Flow[Send, ByteString, NotUsed] = Flow[Send].map { sendEnvelope ⇒
    val pdu: ByteString = codec.constructMessage(
      sendEnvelope.recipient.localAddressToUse,
      sendEnvelope.recipient,
      Serialization.currentTransportInformation.withValue(Serialization.Information(localAddress.address, system)) {
        MessageSerializer.serialize(system, sendEnvelope.message.asInstanceOf[AnyRef])
      },
      sendEnvelope.senderOption,
      seqOption = None, // FIXME: Acknowledgements will be handled differently I just reused the old codec
      ackOption = None)

    // TODO: Drop unserializable messages
    // TODO: Drop oversized messages
    (new ByteStringBuilder).putInt(pdu.size)(ByteOrder.LITTLE_ENDIAN).result() ++ pdu
  }

  val decoder: Flow[ByteString, AkkaPduCodec.Message, NotUsed] =
    Framing.lengthField(4, maximumFrameLength = 256000)
      .map { frame ⇒
        // TODO: Drop unserializable messages
        val pdu = codec.decodeMessage(frame.drop(4), provider, localAddress.address)._2.get
        pdu
      }

  val messageDispatcherSink: Sink[InboundEnvelope, Future[Done]] = Sink.foreach[InboundEnvelope] { m ⇒
    messageDispatcher.dispatch(m.recipient, m.recipientAddress, m.message, m.senderOption)
  }

  val deserializer: Flow[AkkaPduCodec.Message, InboundEnvelope, NotUsed] =
    Flow[AkkaPduCodec.Message].map { m ⇒
      InboundEnvelope(
        m.recipient,
        m.recipientAddress,
        MessageSerializer.deserialize(system, m.serializedMessage),
        m.senderOption)
    }

  val inboundFlow: Flow[ByteString, ByteString, NotUsed] = {
    Flow.fromSinkAndSource(
      decoder
        .via(deserializer)
        .via(new InboundHandshake(this))
        .to(messageDispatcherSink),
      Source.maybe[ByteString].via(killSwitch.flow))
  }

  val inboundControlFlow: Flow[ByteString, ByteString, ControlMessageSubject] = {
    Flow.fromSinkAndSourceMat(
      decoder
        .via(deserializer)
        .via(new InboundHandshake(this))
        .via(new SystemMessageAcker(this))
        .viaMat(new InboundControlJunction)(Keep.right)
        .to(messageDispatcherSink),
      Source.maybe[ByteString].via(killSwitch.flow))((a, b) ⇒ a)
  }

}

