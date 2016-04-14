/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

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

/**
 * INTERNAL API
 */
// FIXME: Replace the codec with a custom made, hi-perf one
private[remote] abstract class Transport(val localAddress: Address,
                                         val system: ExtendedActorSystem,
                                         val provider: RemoteActorRefProvider,
                                         val codec: AkkaPduCodec,
                                         val inboundDispatcher: InboundMessageDispatcher) {

  val log: LoggingAdapter = Logging(system.eventStream, getClass.getName)

  val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

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

  val messageDispatcher: Sink[AkkaPduCodec.Message, Any] = Sink.foreach[AkkaPduCodec.Message] { m ⇒
    inboundDispatcher.dispatch(m.recipient, m.recipientAddress, m.serializedMessage, m.senderOption)
  }

  val inboundFlow: Flow[ByteString, ByteString, NotUsed] = {
    Flow.fromSinkAndSource(
      decoder.to(messageDispatcher),
      Source.maybe[ByteString].via(killSwitch.flow))
  }

  def start(): Unit

  def shutdown(): Future[Done]

  def outbound(remoteAddress: Address): Sink[Send, Any]
}

/**
 * INTERNAL API
 */
private[remote] class TcpTransport(
  localAddress: Address,
  system: ExtendedActorSystem,
  materializer: Materializer,
  provider: RemoteActorRefProvider,
  codec: AkkaPduCodec,
  inboundDispatcher: InboundMessageDispatcher)
  extends Transport(localAddress, system, provider, codec, inboundDispatcher) {

  @volatile private[this] var binding: Tcp.ServerBinding = _

  override def start(): Unit = {
    binding = Await.result(
      Tcp(system).bindAndHandle(inboundFlow, localAddress.host.get, localAddress.port.get)(materializer),
      3.seconds)
    log.info("Artery TCP started up with address {}", binding.localAddress)
  }

  override def shutdown(): Future[Done] = {
    import system.dispatcher
    if (binding != null) {
      binding.unbind().map(_ ⇒ Done).andThen {
        case _ ⇒ killSwitch.abort(new Exception("System shut down"))
      }
    } else
      Future.successful(Done)
  }

  override def outbound(remoteAddress: Address): Sink[Send, Any] = {
    val remoteInetSocketAddress = new InetSocketAddress(
      remoteAddress.host.get,
      remoteAddress.port.get)

    Flow.fromGraph(killSwitch.flow[Send])
      .via(encoder)
      .via(Tcp(system).outgoingConnection(remoteInetSocketAddress, halfClose = false))
      .to(Sink.ignore)
  }
}

/**
 * INTERNAL API
 */
private[remote] class AeronTransport(
  localAddress: Address,
  system: ExtendedActorSystem,
  materializer: Materializer,
  provider: RemoteActorRefProvider,
  codec: AkkaPduCodec,
  inboundDispatcher: InboundMessageDispatcher)
  extends Transport(localAddress, system, provider, codec, inboundDispatcher) {

  private implicit val mat = materializer
  // TODO support port 0
  private val inboundChannel = s"aeron:udp?endpoint=${localAddress.host.get}:${localAddress.port.get}"

  private val aeron = {
    val ctx = new Aeron.Context
    // TODO also support external media driver
    val driver = MediaDriver.launchEmbedded()
    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    Aeron.connect(ctx)
  }

  override def start(): Unit = {
    Source.fromGraph(new AeronSource(inboundChannel, () ⇒ aeron))
      .map(ByteString.apply) // TODO we should use ByteString all the way
      .via(inboundFlow)
      .runWith(Sink.ignore)
  }

  override def shutdown(): Future[Done] = {
    // FIXME stop the AeronSource first?
    aeron.close()
    Future.successful(Done)
  }

  override def outbound(remoteAddress: Address): Sink[Send, Any] = {
    val outboundChannel = s"aeron:udp?endpoint=${remoteAddress.host.get}:${remoteAddress.port.get}"
    Flow.fromGraph(killSwitch.flow[Send])
      .via(encoder)
      .map(_.toArray) // TODO we should use ByteString all the way
      .to(new AeronSink(outboundChannel, () ⇒ aeron))
  }

  // FIXME we don't need Framing for Aeron, since it has fragmentation
}
