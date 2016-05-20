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
  compressionTable: LiteralCompressionTable)
  extends GraphStage[FlowShape[Send, EnvelopeBuffer]] {

  val in: Inlet[Send] = Inlet("Artery.Encoder.in")
  val out: Outlet[EnvelopeBuffer] = Outlet("Artery.Encoder.out")
  val shape: FlowShape[Send, EnvelopeBuffer] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      private val pool = transport.envelopePool
      private val headerBuilder = HeaderBuilder(compressionTable)
      headerBuilder.version = ArteryTransport.Version
      headerBuilder.uid = transport.localAddress.uid
      private val localAddress = transport.localAddress.address
      private val serialization = SerializationExtension(transport.system)

      private val noSender = transport.system.deadLetters.path.toSerializationFormatWithAddress(localAddress)
      private val senderCache = new java.util.HashMap[ActorRef, String]
      private var recipientCache = new java.util.HashMap[ActorRef, String]

      override def onPush(): Unit = {
        val send = grab(in)
        val envelope = pool.acquire()

        val recipientStr = recipientCache.get(send.recipient) match {
          case null ⇒
            val s = send.recipient.path.toSerializationFormat
            // FIXME this cache will be replaced by compression table
            if (recipientCache.size() >= 1000)
              recipientCache.clear()
            recipientCache.put(send.recipient, s)
            s
          case s ⇒ s
        }
        headerBuilder.recipientActorRef = recipientStr

        send.senderOption match {
          case Some(sender) ⇒
            val senderStr = senderCache.get(sender) match {
              case null ⇒
                val s = sender.path.toSerializationFormatWithAddress(localAddress)
                // FIXME we might need an efficient LRU cache, or replaced by compression table
                if (senderCache.size() >= 1000)
                  senderCache.clear()
                senderCache.put(sender, s)
                s
              case s ⇒ s
            }
            headerBuilder.senderActorRef = senderStr
          case None ⇒
            //headerBuilder.setNoSender()
            headerBuilder.senderActorRef = noSender
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
      private val serialization = SerializationExtension(transport.system)

      private val recipientCache = new java.util.HashMap[String, InternalActorRef]
      private val senderCache = new java.util.HashMap[String, Option[ActorRef]]

      override def onPush(): Unit = {
        val envelope = grab(in)
        envelope.parseHeader(headerBuilder)

        //println(s"${headerBuilder.recipientActorRef} <-- ${headerBuilder.senderActorRef} ${headerBuilder.classManifest}")

        // FIXME: Instead of using Strings, the headerBuilder should automatically return cached ActorRef instances
        // in case of compression is enabled
        // FIXME: Is localAddress really needed?
        val recipient: InternalActorRef = recipientCache.get(headerBuilder.recipientActorRef) match {
          case null ⇒
            val ref = provider.resolveActorRefWithLocalAddress(headerBuilder.recipientActorRef, localAddress)
            // FIXME we might need an efficient LRU cache, or replaced by compression table
            if (recipientCache.size() >= 1000)
              recipientCache.clear()
            recipientCache.put(headerBuilder.recipientActorRef, ref)
            ref
          case ref ⇒ ref
        }

        val senderOption: Option[ActorRef] = senderCache.get(headerBuilder.senderActorRef) match {
          case null ⇒
            val ref = provider.resolveActorRefWithLocalAddress(headerBuilder.senderActorRef, localAddress)
            // FIXME this cache will be replaced by compression table
            if (senderCache.size() >= 1000)
              senderCache.clear()
            val refOpt = Some(ref)
            senderCache.put(headerBuilder.senderActorRef, refOpt)
            refOpt
          case refOpt ⇒ refOpt
        }

        val decoded = InboundEnvelope(
          recipient,
          localAddress, // FIXME: Is this needed anymore? What should we do here?
          MessageSerializer.deserializeForArtery(transport.system, serialization, headerBuilder, envelope),
          senderOption, // FIXME: No need for an option, decode simply to deadLetters instead
          UniqueAddress(senderOption.get.path.address, headerBuilder.uid)) // FIXME see issue #20568

        pool.release(envelope)
        push(out, decoded)
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}
