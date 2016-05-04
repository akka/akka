/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.duration._
import akka.actor.Props
import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.nio.ByteOrder
import akka.NotUsed
import akka.actor.{ Address, ExtendedActorSystem }
import akka.remote.EndpointManager.Send
import akka.remote.{ InboundMessageDispatcher, MessageSerializer, RemoteActorRefProvider }
import akka.remote.transport.AkkaPduCodec
import akka.serialization.Serialization
import akka.stream.{ KillSwitches, SharedKillSwitch }
import akka.stream.scaladsl.{ Flow, Framing, Sink, Source, Tcp }
import akka.util.{ ByteString, ByteStringBuilder }
import scala.concurrent.Future
import akka.Done
import akka.stream.Materializer
import scala.concurrent.Await
import akka.event.LoggingAdapter
import akka.event.Logging
import io.aeron.driver.MediaDriver
import io.aeron.Aeron
import org.agrona.ErrorHandler
import io.aeron.AvailableImageHandler
import io.aeron.Image
import io.aeron.UnavailableImageHandler
import io.aeron.exceptions.ConductorServiceTimeoutException
import akka.actor.LocalRef
import akka.actor.InternalActorRef
import akka.dispatch.sysmsg.SystemMessage
import akka.actor.PossiblyHarmful
import akka.actor.RepointableRef
import akka.actor.ActorSelectionMessage
import akka.remote.RemoteRef
import akka.actor.ActorSelection
import akka.actor.ActorRef
import akka.stream.scaladsl.Keep

/**
 * INTERNAL API
 */
private[akka] object Transport {
  // FIXME avoid allocating this envelope?
  final case class InboundEnvelope(
    recipient: InternalActorRef,
    recipientAddress: Address,
    message: AnyRef,
    senderOption: Option[ActorRef])
}

/**
 * INTERNAL API
 */
// FIXME: Replace the codec with a custom made, hi-perf one
private[akka] class Transport(
  val localAddress: Address,
  val system: ExtendedActorSystem,
  val materializer: Materializer,
  val provider: RemoteActorRefProvider,
  val codec: AkkaPduCodec) {
  import Transport._

  private val log: LoggingAdapter = Logging(system.eventStream, getClass.getName)
  private val remoteDaemon = provider.remoteDaemon

  private implicit val mat = materializer
  // TODO support port 0
  private val inboundChannel = s"aeron:udp?endpoint=${localAddress.host.get}:${localAddress.port.get}"
  private def outboundChannel(a: Address) = s"aeron:udp?endpoint=${a.host.get}:${a.port.get}"
  private val systemMessageStreamId = 1
  private val ordinaryStreamId = 3

  private val systemMessageResendInterval: FiniteDuration = 1.second // FIXME config

  private var systemMessageReplyJunction: SystemMessageReplyJunction.Junction = _

  // Need an ActorRef that is passed in the `SystemMessageEnvelope.ackReplyTo`.
  // Those messages are not actually handled by this actor, but intercepted by the
  // SystemMessageReplyJunction stage.
  private val systemMessageReplyRecepient = system.systemActorOf(Props.empty, "systemMessageReplyTo")

  private val driver = {
    // TODO also support external media driver
    val driverContext = new MediaDriver.Context
    // FIXME settings from config
    driverContext.clientLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.imageLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.driverTimeoutMs(SECONDS.toNanos(10))
    MediaDriver.launchEmbedded(driverContext)
  }

  private val aeron = {
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
    Aeron.connect(ctx)
  }

  private val taskRunner = new TaskRunner(system)

  def start(): Unit = {
    taskRunner.start()
    systemMessageReplyJunction = Source.fromGraph(new AeronSource(inboundChannel, systemMessageStreamId, aeron, taskRunner))
      .async // FIXME use dedicated dispatcher for AeronSource
      .map(ByteString.apply) // TODO we should use ByteString all the way
      .viaMat(inboundSystemMessageFlow)(Keep.right)
      .to(Sink.ignore)
      .run()
    Source.fromGraph(new AeronSource(inboundChannel, ordinaryStreamId, aeron, taskRunner))
      .async // FIXME use dedicated dispatcher for AeronSource
      .map(ByteString.apply) // TODO we should use ByteString all the way
      .via(inboundFlow)
      .runWith(Sink.ignore)
  }

  def shutdown(): Future[Done] = {
    // FIXME stop the AeronSource first?
    taskRunner.stop()
    aeron.close()
    driver.close()
    Future.successful(Done)
  }

  val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

  def outbound(remoteAddress: Address): Sink[Send, Any] = {
    Flow.fromGraph(killSwitch.flow[Send])
      .via(encoder)
      .map(_.toArray) // TODO we should use ByteString all the way
      .to(new AeronSink(outboundChannel(remoteAddress), ordinaryStreamId, aeron, taskRunner))
  }

  def outboundSystemMessage(remoteAddress: Address): Sink[Send, Any] = {
    Flow.fromGraph(killSwitch.flow[Send])
      .via(new SystemMessageDelivery(systemMessageReplyJunction, systemMessageResendInterval,
        localAddress, remoteAddress, systemMessageReplyRecepient))
      .via(encoder)
      .map(_.toArray) // TODO we should use ByteString all the way
      .to(new AeronSink(outboundChannel(remoteAddress), systemMessageStreamId, aeron, taskRunner))
  }

  // TODO: Try out parallelized serialization (mapAsync) for performance
  val encoder: Flow[Send, ByteString, NotUsed] = Flow[Send].map { sendEnvelope ⇒
    val pdu: ByteString = codec.constructMessage(
      sendEnvelope.recipient.localAddressToUse,
      sendEnvelope.recipient,
      Serialization.currentTransportInformation.withValue(Serialization.Information(localAddress, system)) {
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
        val pdu = codec.decodeMessage(frame.drop(4), provider, localAddress)._2.get
        pdu
      }

  val messageDispatcher: Sink[InboundEnvelope, Future[Done]] = Sink.foreach[InboundEnvelope] { m ⇒
    dispatchInboundMessage(m.recipient, m.recipientAddress, m.message, m.senderOption)
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
      decoder.via(deserializer).to(messageDispatcher),
      Source.maybe[ByteString].via(killSwitch.flow))
  }

  val inboundSystemMessageFlow: Flow[ByteString, ByteString, SystemMessageReplyJunction.Junction] = {
    Flow.fromSinkAndSourceMat(
      decoder.via(deserializer)
        .via(new SystemMessageAcker(localAddress))
        .viaMat(new SystemMessageReplyJunction)(Keep.right)
        .to(messageDispatcher),
      Source.maybe[ByteString].via(killSwitch.flow))((a, b) ⇒ a)
  }

  private def dispatchInboundMessage(recipient: InternalActorRef,
                                     recipientAddress: Address,
                                     message: AnyRef,
                                     senderOption: Option[ActorRef]): Unit = {

    import provider.remoteSettings._

    val sender: ActorRef = senderOption.getOrElse(system.deadLetters)
    val originalReceiver = recipient.path

    def msgLog = s"RemoteMessage: [$message] to [$recipient]<+[$originalReceiver] from [$sender()]"

    recipient match {

      case `remoteDaemon` ⇒
        if (UntrustedMode) log.debug("dropping daemon message in untrusted mode")
        else {
          if (LogReceive) log.debug("received daemon message {}", msgLog)
          remoteDaemon ! message
        }

      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (LogReceive) log.debug("received local message {}", msgLog)
        message match {
          case sel: ActorSelectionMessage ⇒
            if (UntrustedMode && (!TrustedSelectionPaths.contains(sel.elements.mkString("/", "/", "")) ||
              sel.msg.isInstanceOf[PossiblyHarmful] || l != provider.rootGuardian))
              log.debug("operating in UntrustedMode, dropping inbound actor selection to [{}], " +
                "allow it by adding the path to 'akka.remote.trusted-selection-paths' configuration",
                sel.elements.mkString("/", "/", ""))
            else
              // run the receive logic for ActorSelectionMessage here to make sure it is not stuck on busy user actor
              ActorSelection.deliverSelection(l, sender, sel)
          case msg: PossiblyHarmful if UntrustedMode ⇒
            log.debug("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type [{}]", msg.getClass.getName)
          case msg: SystemMessage ⇒ l.sendSystemMessage(msg)
          case msg                ⇒ l.!(msg)(sender)
        }

      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal && !UntrustedMode ⇒
        if (LogReceive) log.debug("received remote-destined message {}", msgLog)
        if (provider.transport.addresses(recipientAddress))
          // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
          r.!(message)(sender)
        else
          log.error("dropping message [{}] for non-local recipient [{}] arriving at [{}] inbound addresses are [{}]",
            message.getClass, r, recipientAddress, provider.transport.addresses.mkString(", "))

      case r ⇒ log.error("dropping message [{}] for unknown recipient [{}] arriving at [{}] inbound addresses are [{}]",
        message.getClass, r, recipientAddress, provider.transport.addresses.mkString(", "))

    }
  }

}
