/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.{ EmptyLocalActorRef, _ }
import akka.event.Logging
import akka.remote.artery.Decoder.{ AdvertiseActorRefsCompressionTable, AdvertiseClassManifestsCompressionTable, InboundCompressionAccess, InboundCompressionAccessImpl }
import akka.remote.artery.SystemMessageDelivery.SystemMessageEnvelope
import akka.remote.artery.compress.CompressionProtocol._
import akka.remote.artery.compress._
import akka.remote.{ MessageSerializer, OversizedPayloadException, RemoteActorRefProvider, UniqueAddress }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream._
import akka.stream.stage._
import akka.util.{ OptionVal, Unsafe }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal
import akka.util.ByteStringBuilder
import java.nio.ByteOrder
import akka.remote.artery.OutboundHandshake.HandshakeReq
import akka.serialization.SerializerWithStringManifest

/**
 * INTERNAL API
 */
private[remote] object Encoder {
  private[remote] trait OutboundCompressionAccess {
    def changeActorRefCompression(table: CompressionTable[ActorRef]): Future[Done]
    def changeClassManifestCompression(table: CompressionTable[String]): Future[Done]
    def clearCompression(): Future[Done]
  }

  private[remote] class AccessOutboundCompressionFailed
    extends RuntimeException("Change of outbound compression table failed (will be retried), because materialization did not complete yet")

}

/**
 * INTERNAL API
 */
private[remote] class Encoder(
  uniqueLocalAddress:   UniqueAddress,
  system:               ExtendedActorSystem,
  outboundEnvelopePool: ObjectPool[ReusableOutboundEnvelope],
  bufferPool:           EnvelopeBufferPool,
  streamId:             Int,
  debugLogSend:         Boolean,
  version:              Byte)
  extends GraphStageWithMaterializedValue[FlowShape[OutboundEnvelope, EnvelopeBuffer], Encoder.OutboundCompressionAccess] {
  import Encoder._

  val in: Inlet[OutboundEnvelope] = Inlet("Artery.Encoder.in")
  val out: Outlet[EnvelopeBuffer] = Outlet("Artery.Encoder.out")
  val shape: FlowShape[OutboundEnvelope, EnvelopeBuffer] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, OutboundCompressionAccess) = {
    val logic = new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging with OutboundCompressionAccess {

      private val headerBuilder = HeaderBuilder.out()
      headerBuilder setVersion version
      headerBuilder setUid uniqueLocalAddress.uid
      private val localAddress = uniqueLocalAddress.address
      private val serialization = SerializationExtension(system)
      private val serializationInfo = Serialization.Information(localAddress, system)

      private val instruments: RemoteInstruments = RemoteInstruments(system)

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

      private var debugLogSendEnabled = false

      override def preStart(): Unit = {
        debugLogSendEnabled = debugLogSend && log.isDebugEnabled
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

            val startTime: Long = if (instruments.timeSerialization) System.nanoTime else 0
            if (instruments.nonEmpty)
              headerBuilder.setRemoteInstruments(instruments)

            MessageSerializer.serializeForArtery(serialization, outboundEnvelope, headerBuilder, envelope)

            if (instruments.nonEmpty) {
              val time = if (instruments.timeSerialization) System.nanoTime - startTime else 0
              instruments.messageSent(outboundEnvelope, envelope.byteBuffer.position(), time)
            }
          } finally Serialization.currentTransportInformation.value = oldValue

          envelope.byteBuffer.flip()

          if (debugLogSendEnabled)
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
       * External call from ChangeOutboundCompression materialized value
       */
      override def changeActorRefCompression(table: CompressionTable[ActorRef]): Future[Done] = {
        val done = Promise[Done]()
        try changeActorRefCompressionCb.invoke((table, done)) catch {
          // This is a harmless failure, it will be retried on next advertisement or handshake attempt.
          // It will only occur when callback is invoked before preStart. That is highly unlikely to
          // happen since advertisement is not done immediately and handshake involves network roundtrip.
          case NonFatal(_) ⇒ done.tryFailure(new AccessOutboundCompressionFailed)
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
          case NonFatal(_) ⇒ done.tryFailure(new AccessOutboundCompressionFailed)
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
          case NonFatal(_) ⇒ done.tryFailure(new AccessOutboundCompressionFailed)
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

  /** Materialized value of [[Encoder]] which allows safely calling into the stage to interfact with compression tables. */
  private[remote] trait InboundCompressionAccess {
    def confirmActorRefCompressionAdvertisementAck(ack: ActorRefCompressionAdvertisementAck): Future[Done]
    def confirmClassManifestCompressionAdvertisementAck(ack: ClassManifestCompressionAdvertisementAck): Future[Done]
    def closeCompressionFor(originUid: Long): Future[Done]

    /** For testing purposes, usually triggered by timer from within Decoder stage. */
    def runNextActorRefAdvertisement(): Unit
    /** For testing purposes, usually triggered by timer from within Decoder stage. */
    def runNextClassManifestAdvertisement(): Unit
  }

  private[remote] trait InboundCompressionAccessImpl extends InboundCompressionAccess {
    this: GraphStageLogic with StageLogging ⇒

    def compressions: InboundCompressions

    private val closeCompressionForCb = getAsyncCallback[(Long, Promise[Done])] {
      case (uid, done) ⇒
        compressions.close(uid)
        done.success(Done)
    }
    private val confirmActorRefCompressionAdvertisementCb = getAsyncCallback[(ActorRefCompressionAdvertisementAck, Promise[Done])] {
      case (ActorRefCompressionAdvertisementAck(from, tableVersion), done) ⇒
        compressions.confirmActorRefCompressionAdvertisement(from.uid, tableVersion)
        done.success(Done)
    }
    private val confirmClassManifestCompressionAdvertisementCb = getAsyncCallback[(ClassManifestCompressionAdvertisementAck, Promise[Done])] {
      case (ClassManifestCompressionAdvertisementAck(from, tableVersion), done) ⇒
        compressions.confirmClassManifestCompressionAdvertisement(from.uid, tableVersion)
        done.success(Done)
    }
    private val runNextActorRefAdvertisementCb = getAsyncCallback[Unit] {
      _ ⇒ compressions.runNextActorRefAdvertisement()
    }
    private val runNextClassManifestAdvertisementCb = getAsyncCallback[Unit] {
      _ ⇒ compressions.runNextClassManifestAdvertisement()
    }

    // TODO in practice though all those CB's will always succeed, no need for the futures etc IMO

    /**
     * External call from ChangeInboundCompression materialized value
     */
    override def closeCompressionFor(originUid: Long): Future[Done] = {
      val done = Promise[Done]()
      try closeCompressionForCb.invoke((originUid, done)) catch {
        // in case materialization not completed yet
        case NonFatal(_) ⇒ done.tryFailure(new AccessInboundCompressionFailed)
      }
      done.future
    }
    /**
     * External call from ChangeInboundCompression materialized value
     */
    override def confirmActorRefCompressionAdvertisementAck(ack: ActorRefCompressionAdvertisementAck): Future[Done] = {
      val done = Promise[Done]()
      try confirmActorRefCompressionAdvertisementCb.invoke((ack, done)) catch {
        // in case materialization not completed yet
        case NonFatal(_) ⇒ done.tryFailure(new AccessInboundCompressionFailed)
      }
      done.future
    }
    /**
     * External call from ChangeInboundCompression materialized value
     */
    override def confirmClassManifestCompressionAdvertisementAck(ack: ClassManifestCompressionAdvertisementAck): Future[Done] = {
      val done = Promise[Done]()
      try confirmClassManifestCompressionAdvertisementCb.invoke((ack, done)) catch {
        case NonFatal(_) ⇒ done.tryFailure(new AccessInboundCompressionFailed)
      }
      done.future
    }
    /**
     * External call from ChangeInboundCompression materialized value
     */
    override def runNextActorRefAdvertisement(): Unit =
      runNextActorRefAdvertisementCb.invoke(())
    /**
     * External call from ChangeInboundCompression materialized value
     */
    override def runNextClassManifestAdvertisement(): Unit =
      runNextClassManifestAdvertisementCb.invoke(())
  }

  private[remote] class AccessInboundCompressionFailed
    extends RuntimeException("Change of inbound compression table failed (will be retried), because materialization did not complete yet")

  // timer keys
  private case object AdvertiseActorRefsCompressionTable
  private case object AdvertiseClassManifestsCompressionTable

}

/**
 * INTERNAL API
 */
private[remote] final class ActorRefResolveCacheWithAddress(provider: RemoteActorRefProvider, localAddress: UniqueAddress)
  extends LruBoundedCache[String, InternalActorRef](capacity = 1024, evictAgeThreshold = 600) {

  override protected def compute(k: String): InternalActorRef =
    provider.resolveActorRefWithLocalAddress(k, localAddress.address)

  override protected def hash(k: String): Int = Unsafe.fastHash(k)

  override protected def isCacheable(v: InternalActorRef): Boolean = !v.isInstanceOf[EmptyLocalActorRef]
}

/**
 * INTERNAL API
 */
private[remote] class Decoder(
  inboundContext:      InboundContext,
  system:              ExtendedActorSystem,
  uniqueLocalAddress:  UniqueAddress,
  settings:            ArterySettings,
  inboundCompressions: InboundCompressions,
  inEnvelopePool:      ObjectPool[ReusableInboundEnvelope])
  extends GraphStageWithMaterializedValue[FlowShape[EnvelopeBuffer, InboundEnvelope], InboundCompressionAccess] {

  import Decoder.Tick
  val in: Inlet[EnvelopeBuffer] = Inlet("Artery.Decoder.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Decoder.out")
  val shape: FlowShape[EnvelopeBuffer, InboundEnvelope] = FlowShape(in, out)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, InboundCompressionAccess) = {
    val logic = new TimerGraphStageLogic(shape) with InboundCompressionAccessImpl with InHandler with OutHandler with StageLogging {
      import Decoder.RetryResolveRemoteDeployedRecipient

      override val compressions = inboundCompressions

      private val localAddress = inboundContext.localAddress.address
      private val headerBuilder = HeaderBuilder.in(compressions)
      private val actorRefResolver: ActorRefResolveCacheWithAddress =
        new ActorRefResolveCacheWithAddress(system.provider.asInstanceOf[RemoteActorRefProvider], uniqueLocalAddress)
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

        if (settings.Advanced.Compression.Enabled) {
          settings.Advanced.Compression.ActorRefs.AdvertisementInterval match {
            case d: FiniteDuration ⇒ schedulePeriodicallyWithInitialDelay(AdvertiseActorRefsCompressionTable, d, d)
            case _                 ⇒ // not advertising actor ref compressions
          }
          settings.Advanced.Compression.Manifests.AdvertisementInterval match {
            case d: FiniteDuration ⇒ schedulePeriodicallyWithInitialDelay(AdvertiseClassManifestsCompressionTable, d, d)
            case _                 ⇒ // not advertising class manifest compressions
          }
        }
      }
      override def onPush(): Unit = try {
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
            log.warning("Couldn't decompress sender from originUid [{}]. {}", originUid, e)
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
            log.warning("Couldn't decompress sender from originUid [{}]. {}", originUid, e)
            OptionVal.None
        }

        val classManifestOpt = try headerBuilder.manifest(originUid) catch {
          case NonFatal(e) ⇒
            // probably version mismatch due to restarted system
            log.warning("Couldn't decompress manifest from originUid [{}]. {}", originUid, e)
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
                    compressions.hitActorRef(originUid, remoteAddress, snd, 1)
                  case OptionVal.None ⇒
                }

                recipient match {
                  case OptionVal.Some(rcp) ⇒
                    compressions.hitActorRef(originUid, remoteAddress, rcp, 1)
                  case OptionVal.None ⇒
                }

                compressions.hitClassManifest(originUid, remoteAddress, classManifest, 1)

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
            association,
            lane = 0)

          if (recipient.isEmpty && !headerBuilder.isNoRecipient) {

            // The remote deployed actor might not be created yet when resolving the
            // recipient for the first message that is sent to it, best effort retry.
            // However, if the retried resolve isn't successful the ref is banned and
            // we will not do the delayed retry resolve again. The reason for that is
            // if many messages are sent to such dead refs the resolve process will slow
            // down other messages.
            val recipientActorRefPath = headerBuilder.recipientActorRefPath.get
            if (bannedRemoteDeployedActorRefs.contains(recipientActorRefPath)) {

              headerBuilder.recipientActorRefPath match {
                case OptionVal.Some(path) ⇒
                  val ref = actorRefResolver.getOrCompute(path)
                  if (ref.isInstanceOf[EmptyLocalActorRef]) log.warning(
                    "Message for banned (terminated, unresolved) remote deployed recipient [{}].",
                    recipientActorRefPath)
                  push(out, decoded.withRecipient(ref))
                case OptionVal.None ⇒
                  log.warning(
                    "Dropping message for banned (terminated, unresolved) remote deployed recipient [{}].",
                    recipientActorRefPath)
                  pull(in)
              }

            } else
              scheduleOnce(RetryResolveRemoteDeployedRecipient(
                retryResolveRemoteDeployedRecipientAttempts,
                recipientActorRefPath, decoded), retryResolveRemoteDeployedRecipientInterval)
          } else {
            push(out, decoded)
          }
        }
      } catch {
        case NonFatal(e) ⇒
          log.warning("Dropping message due to: {}", e)
          pull(in)
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

          case AdvertiseActorRefsCompressionTable ⇒
            compressions.runNextActorRefAdvertisement() // TODO: optimise these operations, otherwise they stall the hotpath

          case AdvertiseClassManifestsCompressionTable ⇒
            compressions.runNextClassManifestAdvertisement() // TODO: optimise these operations, otherwise they stall the hotpath

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

    (logic, logic)
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
      private val instruments: RemoteInstruments = RemoteInstruments(system)
      private val serialization = SerializationExtension(system)

      override protected def logSource = classOf[Deserializer]

      override def onPush(): Unit = {
        val envelope = grab(in)

        try {
          val startTime: Long = if (instruments.timeSerialization) System.nanoTime else 0

          val deserializedMessage = MessageSerializer.deserializeForArtery(
            system, envelope.originUid, serialization, envelope.serializer, envelope.classManifest, envelope.envelopeBuffer)

          val envelopeWithMessage = envelope.withMessage(deserializedMessage)

          if (instruments.nonEmpty) {
            instruments.deserialize(envelopeWithMessage)
            val time = if (instruments.timeSerialization) System.nanoTime - startTime else 0
            instruments.messageReceived(envelopeWithMessage, envelope.envelopeBuffer.byteBuffer.limit(), time)
          }
          push(out, envelopeWithMessage)
        } catch {
          case NonFatal(e) ⇒
            val from = envelope.association match {
              case OptionVal.Some(a) ⇒ a.remoteAddress
              case OptionVal.None    ⇒ "unknown"
            }
            log.warning(
              "Failed to deserialize message from [{}] with serializer id [{}] and manifest [{}]. {}",
              from, envelope.serializer, envelope.classManifest, e)
            pull(in)
        } finally {
          val buf = envelope.envelopeBuffer
          envelope.releaseEnvelopeBuffer()
          bufferPool.release(buf)
        }
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API: The HandshakeReq message must be passed in each inbound lane to
 * ensure that it arrives before any application message. Otherwise there is a risk
 * that an application message arrives in the InboundHandshake stage before the
 * handshake is completed and then it would be dropped.
 */
private[remote] class DuplicateHandshakeReq(
  numberOfLanes:  Int,
  inboundContext: InboundContext,
  system:         ExtendedActorSystem,
  bufferPool:     EnvelopeBufferPool) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {

  val in: Inlet[InboundEnvelope] = Inlet("Artery.DuplicateHandshakeReq.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.DuplicateHandshakeReq.out")
  val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private val (serializerId, manifest) = {
        val serialization = SerializationExtension(system)
        val ser = serialization.serializerFor(classOf[HandshakeReq])
        val m = ser match {
          case s: SerializerWithStringManifest ⇒
            s.manifest(HandshakeReq(inboundContext.localAddress, inboundContext.localAddress.address))
          case _ ⇒ ""
        }
        (ser.identifier, m)
      }
      var currentIterator: Iterator[InboundEnvelope] = Iterator.empty

      override def onPush(): Unit = {
        val envelope = grab(in)
        if (envelope.association.isEmpty && envelope.serializer == serializerId && envelope.classManifest == manifest) {
          // only need to duplicate HandshakeReq before handshake is completed
          try {
            currentIterator = Vector.tabulate(numberOfLanes)(i ⇒ envelope.copyForLane(i)).iterator
            push(out, currentIterator.next())
          } finally {
            val buf = envelope.envelopeBuffer
            if (buf != null) {
              envelope.releaseEnvelopeBuffer()
              bufferPool.release(buf)
            }
          }
        } else
          push(out, envelope)
      }

      override def onPull(): Unit = {
        if (currentIterator.isEmpty)
          pull(in)
        else {
          push(out, currentIterator.next())
          if (currentIterator.isEmpty) currentIterator = Iterator.empty // GC friendly
        }
      }

      setHandlers(in, out, this)
    }
}
