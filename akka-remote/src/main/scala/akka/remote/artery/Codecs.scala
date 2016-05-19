package akka.remote.artery

import akka.actor.{ ActorRef, InternalActorRef }
import akka.remote.EndpointManager.Send
import akka.remote.{ MessageSerializer, UniqueAddress }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

// TODO: Long UID
class Encoder(
  transport: ArteryTransport,
  compressionTable: LiteralCompressionTable,
  pool: EnvelopeBufferPool)
  extends GraphStage[FlowShape[Send, EnvelopeBuffer]] {

  val in: Inlet[Send] = Inlet("Artery.Encoder.in")
  val out: Outlet[EnvelopeBuffer] = Outlet("Artery.Encoder.out")
  val shape: FlowShape[Send, EnvelopeBuffer] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      private val headerBuilder = HeaderBuilder(compressionTable)
      headerBuilder.version = ArteryTransport.Version
      headerBuilder.uid = transport.localAddress.uid
      private val localAddress = transport.localAddress.address
      private val serialization = SerializationExtension(transport.system)

      override def onPush(): Unit = {
        val send = grab(in)
        val envelope = pool.acquire()

        headerBuilder.recipientActorRef = send.recipient.path.toSerializationFormat
        send.senderOption match {
          case Some(sender) ⇒
            headerBuilder.senderActorRef = sender.path.toSerializationFormatWithAddress(localAddress)
          case None ⇒
            //headerBuilder.setNoSender()
            headerBuilder.senderActorRef = transport.system.deadLetters.path.toSerializationFormatWithAddress(localAddress)
        }

        // FIXME: Thunk allocation
        Serialization.currentTransportInformation.withValue(Serialization.Information(localAddress, transport.system)) {
          MessageSerializer.serializeForArtery(serialization, send.message.asInstanceOf[AnyRef], headerBuilder, envelope)
        }

        //println(s"${headerBuilder.senderActorRef} --> ${headerBuilder.recipientActorRef} ${headerBuilder.classManifest}")

        envelope.byteBuffer.flip()
        push(out, envelope)
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

class Decoder(
  transport: ArteryTransport,
  compressionTable: LiteralCompressionTable) extends GraphStage[FlowShape[EnvelopeBuffer, InboundEnvelope]] {
  val in: Inlet[EnvelopeBuffer] = Inlet("Artery.Decoder.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Decoder.out")
  val shape: FlowShape[EnvelopeBuffer, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private val pool = transport.envelopePool
      private val localAddress = transport.localAddress.address
      private val provider = transport.provider
      private val headerBuilder = HeaderBuilder(compressionTable)

      override def onPush(): Unit = {
        val envelope = grab(in)
        envelope.parseHeader(headerBuilder)

        //println(s"${headerBuilder.recipientActorRef} <-- ${headerBuilder.senderActorRef} ${headerBuilder.classManifest}")

        // FIXME: Instead of using Strings, the headerBuilder should automatically return cached ActorRef instances
        // in case of compression is enabled
        // FIXME: Is localAddress really needed?
        val recipient: InternalActorRef =
          provider.resolveActorRefWithLocalAddress(headerBuilder.recipientActorRef, localAddress)
        val sender: ActorRef =
          provider.resolveActorRefWithLocalAddress(headerBuilder.senderActorRef, localAddress)

        val decoded = InboundEnvelope(
          recipient,
          localAddress, // FIXME: Is this needed anymore? What should we do here?
          MessageSerializer.deserializeForArtery(transport.system, headerBuilder, envelope),
          Some(sender), // FIXME: No need for an option, decode simply to deadLetters instead
          UniqueAddress(sender.path.address, headerBuilder.uid))

        pool.release(envelope)
        push(out, decoded)
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}
