package akka.remote.artery

import scala.util.control.NonFatal

import akka.actor.{ ActorRef, InternalActorRef }
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.remote.{ MessageSerializer, UniqueAddress }
import akka.remote.EndpointManager.Send
import akka.remote.artery.SystemMessageDelivery.SystemMessageEnvelope
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

// TODO: Long UID
class Encoder(
  uniqueLocalAddress: UniqueAddress,
  system:             ActorSystem,
  compressionTable:   LiteralCompressionTable,
  pool:               EnvelopeBufferPool)
  extends GraphStage[FlowShape[Send, EnvelopeBuffer]] {

  val in: Inlet[Send] = Inlet("Artery.Encoder.in")
  val out: Outlet[EnvelopeBuffer] = Outlet("Artery.Encoder.out")
  val shape: FlowShape[Send, EnvelopeBuffer] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

      private val headerBuilder = HeaderBuilder(compressionTable)
      headerBuilder.version = ArteryTransport.Version
      headerBuilder.uid = uniqueLocalAddress.uid
      private val localAddress = uniqueLocalAddress.address
      private val serialization = SerializationExtension(system)
      private val serializationInfo = Serialization.Information(localAddress, system)

      private val noSender = system.deadLetters.path.toSerializationFormatWithAddress(localAddress)
      private val senderCache = new java.util.HashMap[ActorRef, String]
      private var recipientCache = new java.util.HashMap[ActorRef, String]

      override protected def logSource = classOf[Encoder]

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

        try {
          // avoiding currentTransportInformation.withValue due to thunk allocation
          val oldValue = Serialization.currentTransportInformation.value
          try {
            Serialization.currentTransportInformation.value = serializationInfo
            MessageSerializer.serializeForArtery(serialization, send.message.asInstanceOf[AnyRef], headerBuilder, envelope)
          } finally
            Serialization.currentTransportInformation.value = oldValue

          envelope.byteBuffer.flip()
          push(out, envelope)

        } catch {
          case NonFatal(e) ⇒
            pool.release(envelope)
            send.message match {
              case _: SystemMessageEnvelope ⇒
                log.error(e, "Failed to serialize system message [{}].", send.message.getClass.getName)
                throw e
              case _ ⇒
                log.error(e, "Failed to serialize message [{}].", send.message.getClass.getName)
                pull(in)
            }
        }

      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

class Decoder(
  uniqueLocalAddress:              UniqueAddress,
  system:                          ExtendedActorSystem,
  resolveActorRefWithLocalAddress: String ⇒ InternalActorRef,
  compressionTable:                LiteralCompressionTable,
  pool:                            EnvelopeBufferPool) extends GraphStage[FlowShape[EnvelopeBuffer, InboundEnvelope]] {
  val in: Inlet[EnvelopeBuffer] = Inlet("Artery.Decoder.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Decoder.out")
  val shape: FlowShape[EnvelopeBuffer, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      private val localAddress = uniqueLocalAddress.address
      private val headerBuilder = HeaderBuilder(compressionTable)
      private val serialization = SerializationExtension(system)

      private val recipientCache = new java.util.HashMap[String, InternalActorRef]
      private val senderCache = new java.util.HashMap[String, Option[ActorRef]]

      override protected def logSource = classOf[Decoder]

      override def onPush(): Unit = {
        val envelope = grab(in)
        envelope.parseHeader(headerBuilder)

        // FIXME: Instead of using Strings, the headerBuilder should automatically return cached ActorRef instances
        // in case of compression is enabled
        // FIXME: Is localAddress really needed?
        val recipient: InternalActorRef = recipientCache.get(headerBuilder.recipientActorRef) match {
          case null ⇒
            val ref = resolveActorRefWithLocalAddress(headerBuilder.recipientActorRef)
            // FIXME we might need an efficient LRU cache, or replaced by compression table
            if (recipientCache.size() >= 1000)
              recipientCache.clear()
            recipientCache.put(headerBuilder.recipientActorRef, ref)
            ref
          case ref ⇒ ref
        }

        val senderOption: Option[ActorRef] = senderCache.get(headerBuilder.senderActorRef) match {
          case null ⇒
            val ref = resolveActorRefWithLocalAddress(headerBuilder.senderActorRef)
            // FIXME this cache will be replaced by compression table
            if (senderCache.size() >= 1000)
              senderCache.clear()
            val refOpt = Some(ref)
            senderCache.put(headerBuilder.senderActorRef, refOpt)
            refOpt
          case refOpt ⇒ refOpt
        }

        try {
          val deserializedMessage = MessageSerializer.deserializeForArtery(
            system, serialization, headerBuilder, envelope)

          val decoded = InboundEnvelope(
            recipient,
            localAddress, // FIXME: Is this needed anymore? What should we do here?
            deserializedMessage,
            senderOption, // FIXME: No need for an option, decode simply to deadLetters instead
            headerBuilder.uid)

          push(out, decoded)
        } catch {
          case NonFatal(e) ⇒
            log.warning(
              "Failed to deserialize message with serializer id [{}] and manifest [{}]. {}",
              headerBuilder.serializer, headerBuilder.manifest, e.getMessage)
            pull(in)
        } finally {
          pool.release(envelope)
        }
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}
