/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import java.util.Queue

import akka.actor.{ ActorRef, Terminated }
import akka.event.Logging
import akka.stream._
import akka.stream.actor.{ MaxInFlightRequestStrategy, RequestStrategy, WatermarkRequestStrategy }
import akka.stream.impl.FixedSizeBuffer
import akka.stream.remote.StreamRefs
import akka.stream.remote.impl.StreamRefsMaster
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }

object SinkRef {
  def source[T](): Source[T, Future[SinkRef[T]]] =
    Source.fromGraph(new SinkRefTargetSource[T]()) // TODO settings?

  def bulkTransferSource(port: Int = -1): Source[ByteString, SinkRef[ByteString]] = {
    ???
  }
}

/**
 * This stage can only handle a single "sender" (it does not merge values);
 * The first that pushes is assumed the one we are to trust
 */
final class SinkRefTargetSource[T]() extends GraphStageWithMaterializedValue[SourceShape[T], Future[SinkRef[T]]] {
  val out: Outlet[T] = Outlet[T](s"${Logging.simpleName(getClass)}.out")
  override def shape = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[SinkRef[T]]()

    val logic = new TimerGraphStageLogic(shape) with StageLogging with OutHandler {
      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
      private[this] lazy val settings = streamRefsMaster.settings

      private[this] var self: GraphStageLogic.StageActor = _
      private[this] lazy val selfActorName = streamRefsMaster.nextSinkRefTargetSourceName()
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

      private var remotePartner: ActorRef = _

      override def preStart(): Unit = {
        localCumulativeDemand = settings.initialDemand.toLong

        self = getStageActor(initialReceive, name = selfActorName)
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

            log.warning("[{}] Demanding until [{}] (+{})", selfActorName, localCumulativeDemand, addDemand)
            remotePartner ! demand
            scheduleDemandRedelivery()
          }
        }

      val DemandRedeliveryTimerKey = "DemandRedeliveryTimerKey"
      def scheduleDemandRedelivery() = scheduleOnce(DemandRedeliveryTimerKey, settings.demandRedeliveryInterval)
      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case DemandRedeliveryTimerKey ⇒
          log.debug("[{}] Scheduled re-delivery of demand until [{}]", selfActorName, localCumulativeDemand)
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
    s"${Logging.simpleName(getClass)}()}"
}

/**
 * The "handed out" side of a SinkRef. It powers a Source on the other side.
 * TODO naming!??!?!!?!?!?!
 *
 * Do not create this instance directly, but use `SinkRef` factories, to run/setup its targetRef
 */
final class SinkRef[In] private[akka] ( // TODO is it more of a SourceRefSink?
  private[akka] val targetRef:     ActorRef,
  private[akka] val initialDemand: Long
) extends GraphStage[SinkShape[In]] with Serializable { stage ⇒
  import akka.stream.remote.StreamRefs._

  val in = Inlet[In](s"${Logging.simpleName(getClass)}($targetRef).in")
  override def shape: SinkShape[In] = SinkShape.of(in)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with StageLogging with InHandler {

    private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
    private[this] lazy val selfActorName = streamRefsMaster.nextSinkRefName()

    // we assume that there is at least SOME buffer space
    private[this] var remoteCumulativeDemandReceived = initialDemand

    // FIXME this one will be sent over remoting so we have to be able to make that work
    private[this] var remoteCumulativeDemandConsumed = 0L
    private[this] var self: GraphStageLogic.StageActor = _
    implicit def selfSender: ActorRef = self.ref

    override def preStart(): Unit = {
      self = getStageActor(initialReceive, selfActorName)
      self.watch(targetRef)

      log.warning("Created SinkRef, pointing to remote Sink receiver: {}, local worker: {}", targetRef, self)

      pull(in)
    }

    lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
      case (_, Terminated(`targetRef`)) ⇒
        failStage(failRemoteTerminated())

      case (sender, CumulativeDemand(d)) ⇒
        validatePartnerRef(sender)

        if (remoteCumulativeDemandReceived < d) {
          remoteCumulativeDemandReceived = d
          log.warning("Received cumulative demand [{}], consumable demand: [{}]", CumulativeDemand(d), remoteCumulativeDemandReceived - remoteCumulativeDemandConsumed)
        }
        tryPull()
    }

    override def onPush(): Unit = {
      val elem = grabSequenced(in)
      targetRef ! elem
      log.warning("Sending sequenced: {} to {}", elem, targetRef)
      tryPull()
    }

    private def tryPull() =
      if (remoteCumulativeDemandConsumed < remoteCumulativeDemandReceived && !hasBeenPulled(in))
        pull(in)

    private def grabSequenced[T](in: Inlet[T]): SequencedOnNext[T] = {
      val onNext = SequencedOnNext(remoteCumulativeDemandConsumed, grab(in))
      remoteCumulativeDemandConsumed += 1
      onNext
    }

    override def onUpstreamFailure(ex: Throwable): Unit = {
      targetRef ! StreamRefs.RemoteSinkFailure(ex.getMessage) // TODO yes / no? At least the message I guess
      self.unwatch(targetRef)
      super.onUpstreamFailure(ex)
    }

    override def onUpstreamFinish(): Unit = {
      targetRef ! StreamRefs.RemoteSinkCompleted(remoteCumulativeDemandConsumed)
      self.unwatch(targetRef)
      super.onUpstreamFinish()
    }

    setHandler(in, this)
  }

  private def validatePartnerRef(ref: ActorRef) = {
    if (ref != targetRef) throw new RuntimeException("Got demand from weird actor! Not the one I trust hmmmm!!!")
  }

  private def failRemoteTerminated() = {
    RemoteStreamRefActorTerminatedException(s"Remote target receiver of data ${targetRef} terminated. Local stream terminating, message loss (on remote side) may have happened.")
  }

  override def toString = s"${Logging.simpleName(getClass)}($targetRef)"

}
