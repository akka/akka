/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.actor._
import akka.remote.{ MessageSerializer, OversizedPayloadException, UniqueAddress }
import akka.remote.artery.SystemMessageDelivery.SystemMessageEnvelope
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.util.{ ByteString, OptionVal, PrettyByteString }
import akka.actor.EmptyLocalActorRef
import akka.remote.artery.compress.{ InboundCompressions, OutboundCompressions, OutboundCompressionsImpl }
import akka.stream.stage.TimerGraphStageLogic

/**
 * INTERNAL API
 */
private[remote] class Encoder(
  uniqueLocalAddress:   UniqueAddress,
  system:               ActorSystem,
  compression:          OutboundCompressions,
  outboundEnvelopePool: ObjectPool[ReusableOutboundEnvelope],
  bufferPool:           EnvelopeBufferPool)
  extends GraphStage[FlowShape[OutboundEnvelope, EnvelopeBuffer]] {

  val in: Inlet[OutboundEnvelope] = Inlet("Artery.Encoder.in")
  val out: Outlet[EnvelopeBuffer] = Outlet("Artery.Encoder.out")
  val shape: FlowShape[OutboundEnvelope, EnvelopeBuffer] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

      private val headerBuilder = HeaderBuilder.out(compression)
      headerBuilder setVersion ArteryTransport.Version
      headerBuilder setUid uniqueLocalAddress.uid
      private val localAddress = uniqueLocalAddress.address
      private val serialization = SerializationExtension(system)
      private val serializationInfo = Serialization.Information(localAddress, system)

      override protected def logSource = classOf[Encoder]

      override def onPush(): Unit = {
        val outboundEnvelope = grab(in)
        val envelope = bufferPool.acquire()

        // FIXME: OMG race between setting the version, and using the table!!!!
        headerBuilder setActorRefCompressionTableVersion compression.actorRefCompressionTableVersion
        headerBuilder setClassManifestCompressionTableVersion compression.classManifestCompressionTableVersion

        // internally compression is applied by the builder:
        outboundEnvelope.recipient match {
          case OptionVal.Some(r) ⇒ headerBuilder setRecipientActorRef r
          case OptionVal.None    ⇒ headerBuilder.setNoRecipient()
        }

        try {
          // avoiding currentTransportInformation.withValue due to thunk allocation
          val oldValue = Serialization.currentTransportInformation.value
          try {
            Serialization.currentTransportInformation.value = serializationInfo

            outboundEnvelope.sender match {
              case OptionVal.None    ⇒ headerBuilder.setNoSender()
              case OptionVal.Some(s) ⇒ headerBuilder setSenderActorRef s
            }

            MessageSerializer.serializeForArtery(serialization, outboundEnvelope.message, headerBuilder, envelope)
          } finally Serialization.currentTransportInformation.value = oldValue

          envelope.byteBuffer.flip()
          push(out, envelope)

        } catch {
          case NonFatal(e) ⇒
            bufferPool.release(envelope)
            outboundEnvelope.message match {
              case _: SystemMessageEnvelope ⇒
                log.error(e, "Failed to serialize system message [{}].", outboundEnvelope.message.getClass.getName)
                throw e
              case _ if e.isInstanceOf[java.nio.BufferOverflowException] ⇒
                val reason = new OversizedPayloadException(s"Discarding oversized payload sent to ${outboundEnvelope.recipient}: " +
                  s"max allowed size ${envelope.byteBuffer.limit()} bytes. Message type [${outboundEnvelope.message.getClass.getName}].")
                log.error(reason, "Failed to serialize oversized message [{}].", outboundEnvelope.message.getClass.getName)
                pull(in)
              case _ ⇒
                log.error(e, "Failed to serialize message [{}].", outboundEnvelope.message.getClass.getName)
                pull(in)
            }
        } finally {
          outboundEnvelope match {
            case r: ReusableOutboundEnvelope ⇒ outboundEnvelopePool.release(r)
            case _                           ⇒
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
  compression:                     InboundCompressions, // TODO has to do demuxing on remote address It would seem, as decoder does not yet know
  bufferPool:                      EnvelopeBufferPool,
  inEnvelopePool:                  ObjectPool[ReusableInboundEnvelope]) extends GraphStage[FlowShape[EnvelopeBuffer, InboundEnvelope]] {
  val in: Inlet[EnvelopeBuffer] = Inlet("Artery.Decoder.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Decoder.out")
  val shape: FlowShape[EnvelopeBuffer, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      import Decoder.RetryResolveRemoteDeployedRecipient
      private val localAddress = inboundContext.localAddress.address
      private val headerBuilder = HeaderBuilder.in(compression)
      private val serialization = SerializationExtension(system)

      private val retryResolveRemoteDeployedRecipientInterval = 50.millis
      private val retryResolveRemoteDeployedRecipientAttempts = 20

      override protected def logSource = classOf[Decoder]

      override def onPush(): Unit = {
        val envelope = grab(in)
        envelope.parseHeader(headerBuilder)

        val originUid = headerBuilder.uid
        val association = inboundContext.association(originUid)

        val recipient: OptionVal[InternalActorRef] = headerBuilder.recipientActorRef(originUid) match {
          case OptionVal.Some(ref) ⇒
            OptionVal(ref.asInstanceOf[InternalActorRef])
          case OptionVal.None if headerBuilder.recipientActorRefPath.isDefined ⇒
            resolveRecipient(headerBuilder.recipientActorRefPath.get)
          case _ ⇒
            OptionVal.None
        }

        val sender: OptionVal[InternalActorRef] = headerBuilder.senderActorRef(originUid) match {
          case OptionVal.Some(ref) ⇒
            OptionVal(ref.asInstanceOf[InternalActorRef])
          case OptionVal.None if headerBuilder.senderActorRefPath.isDefined ⇒
            OptionVal(resolveActorRefWithLocalAddress(headerBuilder.senderActorRefPath.get))
          case _ ⇒
            OptionVal.None
        }

        // --- hit refs and manifests for heavy-hitter counting
        association match {
          case OptionVal.Some(assoc) ⇒
            val remoteAddress = assoc.remoteAddress
            if (sender.isDefined) compression.hitActorRef(originUid, headerBuilder.actorRefCompressionTableVersion, remoteAddress, sender.get)
            if (recipient.isDefined) compression.hitActorRef(originUid, headerBuilder.actorRefCompressionTableVersion, remoteAddress, recipient.get)
            compression.hitClassManifest(originUid, headerBuilder.classManifestCompressionTableVersion, remoteAddress, headerBuilder.manifest(originUid))
          case _ ⇒
            // we don't want to record hits for compression while handshake is still in progress.
            log.debug("Decoded message but unable to record hits for compression as no remoteAddress known. No association yet?")
        }
        // --- end of hit refs and manifests for heavy-hitter counting

        try {
          val deserializedMessage = MessageSerializer.deserializeForArtery(
            system, originUid, serialization, headerBuilder, envelope)

          val decoded = inEnvelopePool.acquire().init(
            recipient,
            localAddress, // FIXME: Is this needed anymore? What should we do here?
            deserializedMessage,
            sender, // FIXME: No need for an option, decode simply to deadLetters instead
            originUid,
            association)

          if (recipient.isEmpty && !headerBuilder.isNoRecipient) {
            // the remote deployed actor might not be created yet when resolving the
            // recipient for the first message that is sent to it, best effort retry
            scheduleOnce(RetryResolveRemoteDeployedRecipient(
              retryResolveRemoteDeployedRecipientAttempts,
              headerBuilder.recipientActorRefPath.get, decoded), retryResolveRemoteDeployedRecipientInterval) // FIXME IS THIS SAFE?
          } else
            push(out, decoded)
        } catch {
          case NonFatal(e) ⇒
            log.warning(
              "Failed to deserialize message with serializer id [{}] and manifest [{}]. {}",
              headerBuilder.serializer, headerBuilder.manifest(originUid), e.getMessage)
            pull(in)
        } finally {
          bufferPool.release(envelope)
        }
      }

      private def resolveRecipient(path: String): OptionVal[InternalActorRef] = {
        resolveActorRefWithLocalAddress(path) match {
          case empty: EmptyLocalActorRef ⇒
            val pathElements = empty.path.elements
            // FIXME remote deployment corner case, please fix @patriknw (see also below, in onTimer)
            if (pathElements.nonEmpty && pathElements.head == "remote") OptionVal.None
            else OptionVal(empty)
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
                    recipientPath, inboundEnvelope), retryResolveRemoteDeployedRecipientInterval)
                else {
                  val recipient = resolveActorRefWithLocalAddress(recipientPath)
                  // FIXME only retry for the first message, need to keep them in a cache
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

