/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.actor._
import akka.remote.{ MessageSerializer, OversizedPayloadException, RemoteActorRefProvider, UniqueAddress }
import akka.remote.artery.SystemMessageDelivery.SystemMessageEnvelope
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.util.{ ByteString, OptionVal }
import akka.actor.EmptyLocalActorRef
import akka.remote.artery.compress.InboundCompressions
import akka.stream.stage.TimerGraphStageLogic
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import akka.remote.artery.compress.CompressionTable
import akka.Done
import akka.stream.stage.GraphStageWithMaterializedValue

import scala.concurrent.Promise
import akka.event.Logging

/**
 * INTERNAL API
 */
private[remote] object Encoder {
  private[remote] trait ChangeOutboundCompression {
    def changeActorRefCompression(table: CompressionTable[ActorRef]): Future[Done]
    def changeClassManifestCompression(table: CompressionTable[String]): Future[Done]
    def clearCompression(): Future[Done]
  }

  private[remote] class ChangeOutboundCompressionFailed extends RuntimeException(
    "Change of outbound compression table failed (will be retried), because materialization did not complete yet")

}

/**
 * INTERNAL API
 */
private[remote] class Encoder(
  uniqueLocalAddress:   UniqueAddress,
  system:               ExtendedActorSystem,
  outboundEnvelopePool: ObjectPool[ReusableOutboundEnvelope],
  bufferPool:           EnvelopeBufferPool,
  debugLogSend:         Boolean)
  extends GraphStageWithMaterializedValue[FlowShape[OutboundEnvelope, EnvelopeBuffer], Encoder.ChangeOutboundCompression] {
  import Encoder._

  val in: Inlet[OutboundEnvelope] = Inlet("Artery.Encoder.in")
  val out: Outlet[EnvelopeBuffer] = Outlet("Artery.Encoder.out")
  val shape: FlowShape[OutboundEnvelope, EnvelopeBuffer] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ChangeOutboundCompression) = {
    val logic = new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging with ChangeOutboundCompression {

      private val headerBuilder = HeaderBuilder.out()
      headerBuilder setVersion ArteryTransport.Version
      headerBuilder setUid uniqueLocalAddress.uid
      private val localAddress = uniqueLocalAddress.address
      private val serialization = SerializationExtension(system)
      private val serializationInfo = Serialization.Information(localAddress, system)

      private val instruments: Vector[RemoteInstrument] = RemoteInstruments.create(system)
      // by being backed by an Array, this allows us to not allocate any wrapper type for the metadata (since we need its ID)
      private val serializedMetadatas: MetadataMap[ByteString] = MetadataMap() // TODO: possibly can be optimised a more for the specific access pattern (during write)

      private val changeActorRefCompressionCb = getAsyncCallback[(CompressionTable[ActorRef], Promise[Done])] {
        case (table, done) ⇒
          headerBuilder.setOutboundActorRefCompression(table)
          done.success(Done)
      }

      private val changeClassManifsetCompressionCb = getAsyncCallback[(CompressionTable[String], Promise[Done])] {
        case (table, done) ⇒
          headerBuilder.setOutboundClassManifestCompression(table)
          done.success(Done)
      }

      private val clearCompressionCb = getAsyncCallback[Promise[Done]] { done ⇒
        headerBuilder.setOutboundActorRefCompression(CompressionTable.empty[ActorRef])
        headerBuilder.setOutboundClassManifestCompression(CompressionTable.empty[String])
        done.success(Done)
      }

      override protected def logSource = classOf[Encoder]

      private var debugLogEnabled = false

      override def preStart(): Unit = {
        debugLogEnabled = log.isDebugEnabled
      }

      override def onPush(): Unit = {
        val outboundEnvelope = grab(in)
        val envelope = bufferPool.acquire()

        headerBuilder.resetMessageFields()
        // don't use outbound compression for ArteryMessage, e.g. handshake messages must get through
        // without depending on compression tables being in sync when systems are restarted
        headerBuilder.useOutboundCompression(!outboundEnvelope.message.isInstanceOf[ArteryMessage])

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

            applyAndRenderRemoteMessageSentMetadata(instruments, outboundEnvelope, headerBuilder)
            MessageSerializer.serializeForArtery(serialization, outboundEnvelope.message, headerBuilder, envelope)
          } finally Serialization.currentTransportInformation.value = oldValue

          envelope.byteBuffer.flip()

          if (debugLogSend && debugLogEnabled)
            log.debug(
              "sending remote message [{}] to [{}] from [{}]",
              Logging.messageClassName(outboundEnvelope.message),
              outboundEnvelope.recipient.getOrElse(""), outboundEnvelope.sender.getOrElse(""))

          push(out, envelope)

        } catch {
          case NonFatal(e) ⇒
            bufferPool.release(envelope)
            outboundEnvelope.message match {
              case _: SystemMessageEnvelope ⇒
                log.error(e, "Failed to serialize system message [{}].",
                  Logging.messageClassName(outboundEnvelope.message))
                throw e
              case _ if e.isInstanceOf[java.nio.BufferOverflowException] ⇒
                val reason = new OversizedPayloadException("Discarding oversized payload sent to " +
                  s"${outboundEnvelope.recipient}: max allowed size ${envelope.byteBuffer.limit()} " +
                  s"bytes. Message type [${Logging.messageClassName(outboundEnvelope.message)}].")
                log.error(reason, "Failed to serialize oversized message [{}].",
                  Logging.messageClassName(outboundEnvelope.message))
                pull(in)
              case _ ⇒
                log.error(e, "Failed to serialize message [{}].", Logging.messageClassName(outboundEnvelope.message))
                pull(in)
            }
        } finally {
          outboundEnvelope match {
            case r: ReusableOutboundEnvelope ⇒ outboundEnvelopePool.release(r)
            case _                           ⇒ // no need to release it
          }
        }
      }

      override def onPull(): Unit = pull(in)

      /**
       * Renders metadata into `headerBuilder`.
       *
       * Replace all AnyRef's that were passed along with the [[OutboundEnvelope]] into their [[ByteString]] representations,
       * by calling `remoteMessageSent` of each enabled instrumentation. If `context` was attached in the envelope it is passed
       * into the instrument, otherwise it receives an OptionVal.None as context, and may still decide to attach rendered
       * metadata by returning it.
       */
      private def applyAndRenderRemoteMessageSentMetadata(instruments: Vector[RemoteInstrument], envelope: OutboundEnvelope, headerBuilder: HeaderBuilder): Unit = {
        if (instruments.nonEmpty) {
          val n = instruments.length

          var i = 0
          while (i < n) {
            val instrument = instruments(i)
            val instrumentId = instrument.identifier

            val metadata = instrument.remoteMessageSent(envelope.recipient.orNull, envelope.message, envelope.sender.orNull)
            if (metadata ne null) serializedMetadatas.set(instrumentId, metadata)

            i += 1
          }
        }

        if (serializedMetadatas.nonEmpty) {
          MetadataEnvelopeSerializer.serialize(serializedMetadatas, headerBuilder)
          serializedMetadatas.clear()
        }
      }

      /**
       * External call from ChangeOutboundCompression materialized value
       */
      override def changeActorRefCompression(table: CompressionTable[ActorRef]): Future[Done] = {
        val done = Promise[Done]()
        try changeActorRefCompressionCb.invoke((table, done)) catch {
          // This is a harmless failure, it will be retried on next advertisement or handshake attempt.
          // It will only occur when callback is invoked before preStart. That is highly unlikely to
          // happen since advertisement is not done immediately and handshake involves network roundtrip.
          case NonFatal(_) ⇒ done.tryFailure(new ChangeOutboundCompressionFailed)
        }
        done.future
      }

      /**
       * External call from ChangeOutboundCompression materialized value
       */
      override def changeClassManifestCompression(table: CompressionTable[String]): Future[Done] = {
        val done = Promise[Done]()
        try changeClassManifsetCompressionCb.invoke((table, done)) catch {
          // in case materialization not completed yet
          case NonFatal(_) ⇒ done.tryFailure(new ChangeOutboundCompressionFailed)
        }
        done.future
      }

      /**
       * External call from ChangeOutboundCompression materialized value
       */
      override def clearCompression(): Future[Done] = {
        val done = Promise[Done]()
        try clearCompressionCb.invoke(done) catch {
          // in case materialization not completed yet
          case NonFatal(_) ⇒ done.tryFailure(new ChangeOutboundCompressionFailed)
        }
        done.future
      }

      setHandlers(in, out, this)
    }

    (logic, logic)
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

  private object Tick
}

/**
 * INTERNAL API
 */
private[akka] final class ActorRefResolveCache(provider: RemoteActorRefProvider, localAddress: UniqueAddress)
  extends LruBoundedCache[String, InternalActorRef](capacity = 1024, evictAgeThreshold = 600) {

  override protected def compute(k: String): InternalActorRef =
    provider.resolveActorRefWithLocalAddress(k, localAddress.address)

  override protected def hash(k: String): Int = FastHash.ofString(k)

  override protected def isCacheable(v: InternalActorRef): Boolean = !v.isInstanceOf[EmptyLocalActorRef]
}

/**
 * INTERNAL API
 */
private[remote] class Decoder(
  inboundContext:     InboundContext,
  system:             ExtendedActorSystem,
  uniqueLocalAddress: UniqueAddress,
  compression:        InboundCompressions,
  bufferPool:         EnvelopeBufferPool,
  inEnvelopePool:     ObjectPool[ReusableInboundEnvelope]) extends GraphStage[FlowShape[EnvelopeBuffer, InboundEnvelope]] {
  import Decoder.Tick
  val in: Inlet[EnvelopeBuffer] = Inlet("Artery.Decoder.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Decoder.out")
  val shape: FlowShape[EnvelopeBuffer, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      import Decoder.RetryResolveRemoteDeployedRecipient
      private val localAddress = inboundContext.localAddress.address
      private val headerBuilder = HeaderBuilder.in(compression)
      private val actorRefResolver: ActorRefResolveCache =
        new ActorRefResolveCache(system.provider.asInstanceOf[RemoteActorRefProvider], uniqueLocalAddress)
      private val bannedRemoteDeployedActorRefs = new java.util.HashSet[String]

      private val retryResolveRemoteDeployedRecipientInterval = 50.millis
      private val retryResolveRemoteDeployedRecipientAttempts = 20

      // adaptive sampling when rate > 1000 msg/s
      private var messageCount = 0L
      private var heavyHitterMask = 0 // 0 => no sampling, otherwise power of two - 1
      private val adaptiveSamplingRateThreshold = 1000
      private var tickTimestamp = System.nanoTime()
      private var tickMessageCount = 0L

      override protected def logSource = classOf[Decoder]

      override def preStart(): Unit = {
        schedulePeriodically(Tick, 1.seconds)
      }

      override def onPush(): Unit = {
        messageCount += 1
        val envelope = grab(in)
        headerBuilder.resetMessageFields()
        envelope.parseHeader(headerBuilder)

        val originUid = headerBuilder.uid
        val association = inboundContext.association(originUid)

        val recipient: OptionVal[InternalActorRef] = try headerBuilder.recipientActorRef(originUid) match {
          case OptionVal.Some(ref) ⇒
            OptionVal(ref.asInstanceOf[InternalActorRef])
          case OptionVal.None if headerBuilder.recipientActorRefPath.isDefined ⇒
            resolveRecipient(headerBuilder.recipientActorRefPath.get)
          case _ ⇒
            OptionVal.None
        } catch {
          case NonFatal(e) ⇒
            // probably version mismatch due to restarted system
            log.warning("Couldn't decompress sender from originUid [{}]. {}", originUid, e.getMessage)
            OptionVal.None
        }

        val sender: OptionVal[InternalActorRef] = try headerBuilder.senderActorRef(originUid) match {
          case OptionVal.Some(ref) ⇒
            OptionVal(ref.asInstanceOf[InternalActorRef])
          case OptionVal.None if headerBuilder.senderActorRefPath.isDefined ⇒
            OptionVal(actorRefResolver.getOrCompute(headerBuilder.senderActorRefPath.get))
          case _ ⇒
            OptionVal.None
        } catch {
          case NonFatal(e) ⇒
            // probably version mismatch due to restarted system
            log.warning("Couldn't decompress sender from originUid [{}]. {}", originUid, e.getMessage)
            OptionVal.None
        }

        val classManifestOpt = try headerBuilder.manifest(originUid) catch {
          case NonFatal(e) ⇒
            // probably version mismatch due to restarted system
            log.warning("Couldn't decompress manifest from originUid [{}]. {}", originUid, e.getMessage)
            OptionVal.None
        }

        if ((recipient.isEmpty && headerBuilder.recipientActorRefPath.isEmpty && !headerBuilder.isNoRecipient) ||
          (sender.isEmpty && headerBuilder.senderActorRefPath.isEmpty && !headerBuilder.isNoSender)) {
          log.debug("Dropping message for unknown recipient/sender. It was probably sent from system [{}] with compression " +
            "table [{}] built for previous incarnation of the destination system, or it was compressed with a table " +
            "that has already been discarded in the destination system.", originUid,
            headerBuilder.inboundActorRefCompressionTableVersion)
          pull(in)
        } else if (classManifestOpt.isEmpty) {
          log.debug("Dropping message with unknown manifest. It was probably sent from system [{}] with compression " +
            "table [{}] built for previous incarnation of the destination system, or it was compressed with a table " +
            "that has already been discarded in the destination system.", originUid,
            headerBuilder.inboundActorRefCompressionTableVersion)
          pull(in)
        } else {
          val classManifest = classManifestOpt.get

          if ((messageCount & heavyHitterMask) == 0) {
            // --- hit refs and manifests for heavy-hitter counting
            association match {
              case OptionVal.Some(assoc) ⇒
                val remoteAddress = assoc.remoteAddress
                sender match {
                  case OptionVal.Some(snd) ⇒
                    compression.hitActorRef(originUid, remoteAddress, snd, 1)
                  case OptionVal.None ⇒
                }

                recipient match {
                  case OptionVal.Some(rcp) ⇒
                    compression.hitActorRef(originUid, remoteAddress, rcp, 1)
                  case OptionVal.None ⇒
                }

                compression.hitClassManifest(originUid, remoteAddress, classManifest, 1)

              case _ ⇒
                // we don't want to record hits for compression while handshake is still in progress.
                log.debug("Decoded message but unable to record hits for compression as no remoteAddress known. No association yet?")
            }
            // --- end of hit refs and manifests for heavy-hitter counting
          }

          val decoded = inEnvelopePool.acquire().init(
            recipient,
            sender,
            originUid,
            headerBuilder.serializer,
            classManifest,
            headerBuilder.flags,
            envelope,
            association)

          if (recipient.isEmpty && !headerBuilder.isNoRecipient) {

            // The remote deployed actor might not be created yet when resolving the
            // recipient for the first message that is sent to it, best effort retry.
            // However, if the retried resolve isn't successful the ref is banned and
            // we will not do the delayed retry resolve again. The reason for that is
            // if many messages are sent to such dead refs the resolve process will slow
            // down other messages.
            val recipientActorRefPath = headerBuilder.recipientActorRefPath.get
            if (bannedRemoteDeployedActorRefs.contains(recipientActorRefPath)) {
              log.debug(
                "Dropping message for banned (terminated) remote deployed recipient [{}].",
                recipientActorRefPath)
              pull(in)
            } else
              scheduleOnce(RetryResolveRemoteDeployedRecipient(
                retryResolveRemoteDeployedRecipientAttempts,
                recipientActorRefPath, decoded), retryResolveRemoteDeployedRecipientInterval)
          } else {
            push(out, decoded)
          }
        }
      }

      private def resolveRecipient(path: String): OptionVal[InternalActorRef] = {
        actorRefResolver.getOrCompute(path) match {
          case empty: EmptyLocalActorRef ⇒
            val pathElements = empty.path.elements
            if (pathElements.nonEmpty && pathElements.head == "remote") OptionVal.None
            else OptionVal(empty)
          case ref ⇒ OptionVal(ref)
        }
      }

      override def onPull(): Unit = pull(in)

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case Tick ⇒
            val now = System.nanoTime()
            val d = math.max(1, now - tickTimestamp)
            val rate = (messageCount - tickMessageCount) * TimeUnit.SECONDS.toNanos(1) / d
            val oldHeavyHitterMask = heavyHitterMask
            heavyHitterMask =
              if (rate < adaptiveSamplingRateThreshold) 0 // no sampling
              else if (rate < adaptiveSamplingRateThreshold * 10) (1 << 6) - 1 // sample every 64nth message
              else if (rate < adaptiveSamplingRateThreshold * 100) (1 << 7) - 1 // sample every 128nth message
              else (1 << 8) - 1 // sample every 256nth message
            if (oldHeavyHitterMask > 0 && heavyHitterMask == 0)
              log.debug("Turning off adaptive sampling of compression hit counting")
            else if (oldHeavyHitterMask != heavyHitterMask)
              log.debug("Turning on adaptive sampling ({}nth message) of compression hit counting", heavyHitterMask + 1)
            tickMessageCount = messageCount
            tickTimestamp = now

          case RetryResolveRemoteDeployedRecipient(attemptsLeft, recipientPath, inboundEnvelope) ⇒
            resolveRecipient(recipientPath) match {
              case OptionVal.None ⇒
                if (attemptsLeft > 0)
                  scheduleOnce(RetryResolveRemoteDeployedRecipient(
                    attemptsLeft - 1,
                    recipientPath, inboundEnvelope), retryResolveRemoteDeployedRecipientInterval)
                else {
                  // No more attempts left. If the retried resolve isn't successful the ref is banned and
                  // we will not do the delayed retry resolve again. The reason for that is
                  // if many messages are sent to such dead refs the resolve process will slow
                  // down other messages.
                  if (bannedRemoteDeployedActorRefs.size >= 100) {
                    // keep it bounded
                    bannedRemoteDeployedActorRefs.clear()
                  }
                  bannedRemoteDeployedActorRefs.add(recipientPath)

                  val recipient = actorRefResolver.getOrCompute(recipientPath)
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

/**
 * INTERNAL API
 */
private[remote] class Deserializer(
  inboundContext: InboundContext,
  system:         ExtendedActorSystem,
  bufferPool:     EnvelopeBufferPool) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {

  val in: Inlet[InboundEnvelope] = Inlet("Artery.Deserializer.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Deserializer.out")
  val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      private val instruments: Vector[RemoteInstrument] = RemoteInstruments.create(system)
      private val serialization = SerializationExtension(system)

      override protected def logSource = classOf[Deserializer]

      override def onPush(): Unit = {
        val envelope = grab(in)

        try {
          val deserializedMessage = MessageSerializer.deserializeForArtery(
            system, envelope.originUid, serialization, envelope.serializer, envelope.classManifest, envelope.envelopeBuffer)

          val envelopeWithMessage = envelope.withMessage(deserializedMessage)

          applyIncomingInstruments(envelopeWithMessage)

          push(out, envelopeWithMessage)
        } catch {
          case NonFatal(e) ⇒
            log.warning(
              "Failed to deserialize message with serializer id [{}] and manifest [{}]. {}",
              envelope.serializer, envelope.classManifest, e.getMessage)
            pull(in)
        } finally {
          val buf = envelope.envelopeBuffer
          envelope.releaseEnvelopeBuffer()
          bufferPool.release(buf)
        }
      }

      override def onPull(): Unit = pull(in)

      private def applyIncomingInstruments(envelope: InboundEnvelope): Unit = {
        if (envelope.flag(EnvelopeBuffer.MetadataPresentFlag)) {
          val length = instruments.length
          if (length == 0) {
            // TODO do we need to parse, or can we do a fast forward if debug logging is not enabled?
            val metaMetadataEnvelope = MetadataMapParsing.parse(envelope)
            if (log.isDebugEnabled)
              log.debug("Incoming message envelope contains metadata for instruments: {}, " +
                "however no RemoteInstrument was registered in local system!", metaMetadataEnvelope.metadataMap.keysWithValues.mkString("[", ",", "]"))
          } else {
            // we avoid emitting a MetadataMap and instead directly apply the instruments onto the received metadata
            MetadataMapParsing.applyAllRemoteMessageReceived(instruments, envelope)
          }
        }
      }

      setHandlers(in, out, this)
    }
}
