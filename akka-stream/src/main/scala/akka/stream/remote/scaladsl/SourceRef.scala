/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import akka.NotUsed
import akka.actor.ActorRef
import akka.event.Logging
import akka.stream._
import akka.stream.actor.{ RequestStrategy, WatermarkRequestStrategy }
import akka.stream.impl.FixedSizeBuffer
import akka.stream.remote.StreamRefs
import akka.stream.remote.StreamRefs.{ CumulativeDemand, SequencedOnNext }
import akka.stream.remote.impl.StreamRefsMaster
import akka.stream.scaladsl.{ FlowOps, Sink, Source }
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }

// FIXME IMPLEMENT THIS
object SourceRef {
  def sink[T](): Graph[SinkShape[T], Future[SourceRef[T]]] =
    Sink.fromGraph(new SourceRefOriginSink[T]())

  def bulkTransfer[T](): Graph[SinkShape[ByteString], SourceRef[ByteString]] = ???
}

final class SourceRefOriginSink[T]() extends GraphStageWithMaterializedValue[SinkShape[T], Future[SourceRef[T]]] {
  val in: Inlet[T] = Inlet[T](s"${Logging.simpleName(getClass)}.in")
  override def shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[SourceRef[T]]) = {
    val promise = Promise[SourceRef[T]]

    val logic = new TimerGraphStageLogic(shape) with InHandler with StageLogging {
      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
      private[this] lazy val settings = streamRefsMaster.settings

      private[this] var remotePartner: ActorRef = _

      private[this] var self: GraphStageLogic.StageActor = _
      private[this] lazy val selfActorName = streamRefsMaster.nextSinkRefTargetSourceName()
      private[this] implicit def selfSender: ActorRef = self.ref

      // demand management ---
      private var remoteCumulativeDemandReceived: Long = 0L
      private var remoteCumulativeDemandConsumed: Long = 0L
      // end of demand management ---

      override def preStart(): Unit = {
        self = getStageActor(initialReceive)
        log.warning("Allocated receiver: {}", self.ref)

        promise.success(new SourceRef(self.ref))
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, msg @ StreamRefs.CumulativeDemand(demand)) ⇒
          observeAndValidateSender(sender, "Illegal sender in CumulativeDemand")

          if (demand > remoteCumulativeDemandReceived) {
            remoteCumulativeDemandReceived = demand
            log.warning("Received cumulative demand [{}], consumable demand: [{}]", msg,
              remoteCumulativeDemandReceived - remoteCumulativeDemandConsumed)
          }

          tryPull()
      }

      def tryPull(): Unit =
        if (remoteCumulativeDemandConsumed < remoteCumulativeDemandReceived && !hasBeenPulled(in))
          pull(in)

      private def grabSequenced(in: Inlet[T]): SequencedOnNext[T] = {
        val onNext = SequencedOnNext(remoteCumulativeDemandConsumed, grab(in))
        remoteCumulativeDemandConsumed += 1
        onNext
      }

      override def onPush(): Unit = {
        val elem = grabSequenced(in)
        remotePartner ! elem
        log.warning("Sending sequenced: {} to {}", elem, remotePartner)
        tryPull()
      }

      @throws[StreamRefs.InvalidPartnerActorException]
      def observeAndValidateSender(sender: ActorRef, msg: String): Unit =
        if (remotePartner == null) {
          log.debug("Received first message from {}, assuming it to be the remote partner for this stage", sender)
          remotePartner = sender
          self.watch(sender)
        } else if (sender != remotePartner) {
          throw StreamRefs.InvalidPartnerActorException(sender, remotePartner, msg)
        }

      //      @throws[StreamRefs.InvalidSequenceNumberException]
      //      def observeAndValidateSequenceNr(seqNr: Long, msg: String): Unit =
      //        if (isInvalidSequenceNr(seqNr)) {
      //          throw StreamRefs.InvalidSequenceNumberException(expectingSeqNr, seqNr, msg)
      //        } else {
      //          expectingSeqNr += 1
      //        }
      //      def isInvalidSequenceNr(seqNr: Long): Boolean =
      //        seqNr != expectingSeqNr

      override def onUpstreamFailure(ex: Throwable): Unit = {
        remotePartner ! StreamRefs.RemoteSinkFailure(ex.getMessage) // TODO yes / no? At least the message I guess
        self.unwatch(remotePartner)
        super.onUpstreamFailure(ex)
      }

      override def onUpstreamFinish(): Unit = {
        remotePartner ! StreamRefs.RemoteSinkCompleted(remoteCumulativeDemandConsumed)
        self.unwatch(remotePartner)
        super.onUpstreamFinish()
      }

      setHandler(in, this)
    }

    (logic, promise.future)
  }

}

///// ------------------------------------ FIXME THIS IS A VERBATIM COPY -----------------------------------
///// ------------------------------------ FIXME THIS IS A VERBATIM COPY -----------------------------------
///// ------------------------------------ FIXME THIS IS A VERBATIM COPY -----------------------------------
///// ------------------------------------ FIXME THIS IS A VERBATIM COPY -----------------------------------
/**
 * This stage can only handle a single "sender" (it does not merge values);
 * The first that pushes is assumed the one we are to trust
 */
// FIXME this is basically SinkRefTargetSource
final class SourceRef[T](private[akka] val originRef: ActorRef) extends GraphStageWithMaterializedValue[SourceShape[T], Future[SinkRef[T]]] {
  val out: Outlet[T] = Outlet[T](s"${Logging.simpleName(getClass)}.out")
  override def shape = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[SinkRef[T]]()

    val logic = new TimerGraphStageLogic(shape) with StageLogging with OutHandler {
      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
      private[this] lazy val settings = streamRefsMaster.settings

      private[this] var self: GraphStageLogic.StageActor = _
      private[this] override lazy val stageActorName = streamRefsMaster.nextSinkRefTargetSourceName()
      private[this] implicit def selfSender: ActorRef = self.ref

      // demand management ---
      private val highDemandWatermark = 16

      private var expectingSeqNr: Long = 0L
      private var localCumulativeDemand: Long = 0L // initialized in preStart with settings.initialDemand

      private val receiveBuffer = FixedSizeBuffer[T](highDemandWatermark)

      // TODO configurable?
      // Request strategies talk in terms of Request(n), which we need to translate to cumulative demand
      // TODO the MaxInFlightRequestStrategy is likely better for this use case, yet was a bit weird to use so this one for now
      private val requestStrategy: RequestStrategy = WatermarkRequestStrategy(highWatermark = highDemandWatermark)
      // end of demand management ---

      // TODO we could basically use the other impl... and just pass null as originRef since it'd be obtained from other side...
      private var remotePartner: ActorRef = originRef

      override def preStart(): Unit = {
        localCumulativeDemand = settings.initialDemand.toLong

        self = getStageActor(initialReceive)
        log.warning("Allocated receiver: {}", self.ref)

        promise.success(new SinkRef(self.ref, settings.initialDemand))
      }

      override def onPull(): Unit = {
        tryPush()
        triggerCumulativeDemand()
      }

      def triggerCumulativeDemand(): Unit =
        if (remotePartner ne null) {
          val remainingRequested = java.lang.Long.min(highDemandWatermark, localCumulativeDemand - expectingSeqNr).toInt
          val addDemand = requestStrategy.requestDemand(remainingRequested)

          // only if demand has increased we shoot it right away
          // otherwise it's the same demand level, so it'd be triggered via redelivery anyway
          if (addDemand > 0) {
            localCumulativeDemand += addDemand
            val demand = StreamRefs.CumulativeDemand(localCumulativeDemand)

            log.warning("[{}] Demanding until [{}] (+{})", stageActorName, localCumulativeDemand, addDemand)
            remotePartner ! demand
            scheduleDemandRedelivery()
          }
        }

      val DemandRedeliveryTimerKey = "DemandRedeliveryTimerKey"
      def scheduleDemandRedelivery() = scheduleOnce(DemandRedeliveryTimerKey, settings.demandRedeliveryInterval)
      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case DemandRedeliveryTimerKey ⇒
          log.debug("[{}] Scheduled re-delivery of demand until [{}]", stageActorName, localCumulativeDemand)
          remotePartner ! StreamRefs.CumulativeDemand(localCumulativeDemand)
          scheduleDemandRedelivery()
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, msg @ StreamRefs.SequencedOnNext(seqNr, payload)) ⇒
          observeAndValidateSender(sender, "Illegal sender in SequencedOnNext")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in SequencedOnNext")
          log.warning("Received seq {} from {}", msg, sender)

          triggerCumulativeDemand()
          tryPush(payload)

        case (sender, StreamRefs.RemoteSinkCompleted(seqNr)) ⇒
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkCompleted")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in RemoteSinkCompleted")
          log.debug("The remote Sink has completed, completing this source as well...")

          self.unwatch(sender)
          completeStage()

        case (sender, StreamRefs.RemoteSinkFailure(reason)) ⇒
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkFailure")
          log.debug("The remote Sink has failed, failing (reason: {})", reason)

          self.unwatch(sender)
          failStage(StreamRefs.RemoteStreamRefActorTerminatedException(s"Remote Sink failed, reason: $reason"))
      }

      def tryPush(): Unit =
        if (isAvailable(out) && receiveBuffer.nonEmpty) {
          val elem = receiveBuffer.dequeue()
          log.warning(s"PUSHING SIGNALED ${elem} (capacity: ${receiveBuffer.used}/${receiveBuffer.capacity})")
          push(out, elem)
        }
      def tryPush(payload: Any): Unit =
        if (isAvailable(out)) {
          if (receiveBuffer.nonEmpty) {
            val elem = receiveBuffer.dequeue()
            push(out, elem)
            receiveBuffer.enqueue(payload.asInstanceOf[T])
            log.warning(s"PUSHING SIGNALED ${elem} BUFFERING payload" + payload + s"(capacity: ${receiveBuffer.used}/${receiveBuffer.capacity})")
          } else {
            push(out, payload.asInstanceOf[T])
            log.warning(s"PUSHING DIRECTLY ${payload}")
          }
        } else {
          receiveBuffer.enqueue(payload.asInstanceOf[T])
          log.warning("PUSHING BUFFERING payload" + payload + s"(capacity: ${receiveBuffer.used}/${receiveBuffer.capacity})")
        }

      @throws[StreamRefs.InvalidPartnerActorException]
      def observeAndValidateSender(sender: ActorRef, msg: String): Unit =
        if (remotePartner == null) {
          log.debug("Received first message from {}, assuming it to be the remote partner for this stage", sender)
          remotePartner = sender
          self.watch(sender)
        } else if (sender != remotePartner) {
          throw StreamRefs.InvalidPartnerActorException(sender, remotePartner, msg)
        }

      @throws[StreamRefs.InvalidSequenceNumberException]
      def observeAndValidateSequenceNr(seqNr: Long, msg: String): Unit =
        if (isInvalidSequenceNr(seqNr)) {
          throw StreamRefs.InvalidSequenceNumberException(expectingSeqNr, seqNr, msg)
        } else {
          expectingSeqNr += 1
        }
      def isInvalidSequenceNr(seqNr: Long): Boolean =
        seqNr != expectingSeqNr

      setHandler(out, this)
    }
    (logic, promise.future) // FIXME we'd want to expose just the ref!
  }

  override def toString: String =
    s"${Logging.simpleName(getClass)}($originRef)}"
}

///// ------------------------------------ FIXME END OF THIS IS A VERBATIM COPY ----------------------------
///// ------------------------------------ FIXME END OF THIS IS A VERBATIM COPY ----------------------------
///// ------------------------------------ FIXME END OF THIS IS A VERBATIM COPY ----------------------------
///// ------------------------------------ FIXME END OF THIS IS A VERBATIM COPY ----------------------------
///// ------------------------------------ FIXME END OF THIS IS A VERBATIM COPY ----------------------------
