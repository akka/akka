/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

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

/**
 * INTERNAL API
 */
// FIXME: Replace the codec with a custom made, hi-perf one
private[remote] class Transport(val localAddress: Address,
                                val system: ExtendedActorSystem,
                                val provider: RemoteActorRefProvider,
                                val codec: AkkaPduCodec,
                                val inboundDispatcher: InboundMessageDispatcher) {

  val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

  def outbound(remoteAddress: Address): Sink[Send, Any] = {
    val remoteInetSocketAddress = new InetSocketAddress(
      remoteAddress.host.get,
      remoteAddress.port.get)

    Flow.fromGraph(killSwitch.flow[Send])
      .via(encoder)
      .via(Tcp(system).outgoingConnection(remoteInetSocketAddress, halfClose = false))
      .to(Sink.ignore)
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
