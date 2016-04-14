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
private[remote] class Transport(
  val localAddress: Address,
  val system: ExtendedActorSystem,
  val materializer: Materializer,
  val provider: RemoteActorRefProvider,
  val codec: AkkaPduCodec,
  val inboundDispatcher: InboundMessageDispatcher) {

  private val log: LoggingAdapter = Logging(system.eventStream, getClass.getName)

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

  def start(): Unit = {
    Source.fromGraph(new AeronSource(inboundChannel, () ⇒ aeron))
      .map(ByteString.apply) // TODO we should use ByteString all the way
      .via(inboundFlow)
      .runWith(Sink.ignore)
  }

  def shutdown(): Future[Done] = {
    // FIXME stop the AeronSource first?
    aeron.close()
    Future.successful(Done)
  }

  val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

  def outbound(remoteAddress: Address): Sink[Send, Any] = {
    val outboundChannel = s"aeron:udp?endpoint=${remoteAddress.host.get}:${remoteAddress.port.get}"
    Flow.fromGraph(killSwitch.flow[Send])
      .via(encoder)
      .map(_.toArray) // TODO we should use ByteString all the way
      .to(new AeronSink(outboundChannel, () ⇒ aeron))
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

  val messageDispatcher: Sink[AkkaPduCodec.Message, Any] = Sink.foreach[AkkaPduCodec.Message] { m ⇒
    inboundDispatcher.dispatch(m.recipient, m.recipientAddress, m.serializedMessage, m.senderOption)
  }

  val inboundFlow: Flow[ByteString, ByteString, NotUsed] = {
    Flow.fromSinkAndSource(
      decoder.to(messageDispatcher),
      Source.maybe[ByteString].via(killSwitch.flow))
  }

}
