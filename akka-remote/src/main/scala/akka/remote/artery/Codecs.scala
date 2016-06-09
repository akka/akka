package akka.remote.artery

import scala.concurrent.duration._
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
import akka.util.OptionVal
import akka.actor.EmptyLocalActorRef
import akka.stream.stage.TimerGraphStageLogic

/**
 * INTERNAL API
 */
private[remote] class Encoder(
  uniqueLocalAddress: UniqueAddress,
  system:             ActorSystem,
  compressionTable:   LiteralCompressionTable,
  bufferPool:         EnvelopeBufferPool)
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

      private val senderCache = new java.util.HashMap[ActorRef, String]
      private var recipientCache = new java.util.HashMap[ActorRef, String]

      override protected def logSource = classOf[Encoder]

      override def onPush(): Unit = {
        val send = grab(in)
        val envelope = bufferPool.acquire()

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
          case OptionVal.None ⇒ headerBuilder.setNoSender()
          case OptionVal.Some(sender) ⇒
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
            bufferPool.release(envelope)
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

/**
 * INTERNAL API
 */
private[remote] object Decoder {
  private final case class RetryResolveRemoteDeployedRecipient(
    attemptsLeft:    Int,
    recipientPath:   String,
    inboundEnvelope: InboundEnvelope)
}

/**
 * INTERNAL API
 */
private[remote] class Decoder(
  inboundContext:                  InboundContext,
  system:                          ExtendedActorSystem,
  resolveActorRefWithLocalAddress: String ⇒ InternalActorRef,
  compressionTable:                LiteralCompressionTable,
  bufferPool:                      EnvelopeBufferPool,
  inEnvelopePool:                  ObjectPool[InboundEnvelope]) extends GraphStage[FlowShape[EnvelopeBuffer, InboundEnvelope]] {
  val in: Inlet[EnvelopeBuffer] = Inlet("Artery.Decoder.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Decoder.out")
  val shape: FlowShape[EnvelopeBuffer, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      import Decoder.RetryResolveRemoteDeployedRecipient
      private val localAddress = inboundContext.localAddress.address
      private val headerBuilder = HeaderBuilder(compressionTable)
      private val serialization = SerializationExtension(system)

      private val recipientCache = new java.util.HashMap[String, InternalActorRef]
      private val senderCache = new java.util.HashMap[String, ActorRef]

      private val retryResolveRemoteDeployedRecipientInterval = 50.millis
      private val retryResolveRemoteDeployedRecipientAttempts = 20

      override protected def logSource = classOf[Decoder]

      override def onPush(): Unit = {
        val envelope = grab(in)
        envelope.parseHeader(headerBuilder)

        // FIXME: Instead of using Strings, the headerBuilder should automatically return cached ActorRef instances
        // in case of compression is enabled
        // FIXME: Is localAddress really needed?

        val sender =
          if (headerBuilder.isNoSender)
            OptionVal.None
          else {
            senderCache.get(headerBuilder.senderActorRef) match {
              case null ⇒
                val ref = resolveActorRefWithLocalAddress(headerBuilder.senderActorRef)
                // FIXME this cache will be replaced by compression table
                if (senderCache.size() >= 1000)
                  senderCache.clear()
                senderCache.put(headerBuilder.senderActorRef, ref)
                OptionVal(ref)
              case ref ⇒ OptionVal(ref)
            }
          }

        val recipient =
          if (headerBuilder.isNoRecipient)
            OptionVal.None
          else
            resolveRecipient(headerBuilder.recipientActorRef)

        val originUid = headerBuilder.uid
        val association = inboundContext.association(originUid)

        try {
          val deserializedMessage = MessageSerializer.deserializeForArtery(
            system, serialization, headerBuilder, envelope)

          val decoded = inEnvelopePool.acquire()
          decoded.asInstanceOf[ReusableInboundEnvelope].init(
            recipient,
            localAddress, // FIXME: Is this needed anymore? What should we do here?
            deserializedMessage,
            sender,
            originUid,
            association)

          if (recipient.isEmpty && !headerBuilder.isNoRecipient) {
            // the remote deployed actor might not be created yet when resolving the
            // recipient for the first message that is sent to it, best effort retry
            scheduleOnce(RetryResolveRemoteDeployedRecipient(
              retryResolveRemoteDeployedRecipientAttempts,
              headerBuilder.recipientActorRef, decoded), retryResolveRemoteDeployedRecipientInterval)
          } else
            push(out, decoded)
        } catch {
          case NonFatal(e) ⇒
            log.warning(
              "Failed to deserialize message with serializer id [{}] and manifest [{}]. {}",
              headerBuilder.serializer, headerBuilder.manifest, e.getMessage)
            pull(in)
        } finally {
          bufferPool.release(envelope)
        }
      }

      private def resolveRecipient(path: String): OptionVal[InternalActorRef] = {
        recipientCache.get(path) match {
          case null ⇒
            def addToCache(resolved: InternalActorRef): Unit = {
              // FIXME we might need an efficient LRU cache, or replaced by compression table
              if (recipientCache.size() >= 1000)
                recipientCache.clear()
              recipientCache.put(path, resolved)
            }

            resolveActorRefWithLocalAddress(path) match {
              case empty: EmptyLocalActorRef ⇒
                val pathElements = empty.path.elements
                if (pathElements.nonEmpty && pathElements.head == "remote")
                  OptionVal.None
                else {
                  addToCache(empty)
                  OptionVal(empty)
                }
              case ref ⇒
                addToCache(ref)
                OptionVal(ref)
            }
          case ref ⇒ OptionVal(ref)
        }
      }

      override def onPull(): Unit = pull(in)

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case RetryResolveRemoteDeployedRecipient(attemptsLeft, recipientPath, inboundEnvelope) ⇒
            resolveRecipient(recipientPath) match {
              case OptionVal.None ⇒
                if (attemptsLeft > 0)
                  scheduleOnce(RetryResolveRemoteDeployedRecipient(
                    attemptsLeft - 1,
                    headerBuilder.recipientActorRef, inboundEnvelope), retryResolveRemoteDeployedRecipientInterval)
                else {
                  val recipient = resolveActorRefWithLocalAddress(recipientPath)
                  // only retry for the first message
                  recipientCache.put(recipientPath, recipient)
                  push(out, inboundEnvelope.withRecipient(recipient))
                }
              case OptionVal.Some(recipient) ⇒
                push(out, inboundEnvelope.withRecipient(recipient))
            }
        }
      }

      setHandlers(in, out, this)
    }
}
