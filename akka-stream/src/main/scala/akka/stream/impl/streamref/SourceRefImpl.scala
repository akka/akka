/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.streamref

import akka.NotUsed
import akka.actor.{ ActorRef, Terminated }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.stream._
import akka.stream.impl.FixedSizeBuffer
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.{ OptionVal, PrettyDuration }
import com.github.ghik.silencer.silent

/** INTERNAL API: Implementation class, not intended to be touched directly by end-users */
@InternalApi
private[stream] final case class SourceRefImpl[T](initialPartnerRef: ActorRef) extends SourceRef[T] {
  def source: Source[T, NotUsed] =
    Source.fromGraph(new SourceRefStageImpl(OptionVal.Some(initialPartnerRef))).mapMaterializedValue(_ => NotUsed)
}

/**
 * INTERNAL API
 */
@InternalApi private[stream] object SourceRefStageImpl {
  private sealed trait ActorRefStage { def ref: ActorRef }

  object WatermarkRequestStrategy {

    /**
     * Create [[WatermarkRequestStrategy]] with `lowWatermark` as half of
     * the specified `highWatermark`.
     */
    def apply(highWatermark: Int): WatermarkRequestStrategy = new WatermarkRequestStrategy(highWatermark)
  }

  /**
   * Requests up to the `highWatermark` when the `remainingRequested` is
   * below the `lowWatermark`. This a good strategy when the actor performs work itself.
   */
  final case class WatermarkRequestStrategy(highWatermark: Int, lowWatermark: Int) {
    require(lowWatermark >= 0, "lowWatermark must be >= 0")
    require(highWatermark >= lowWatermark, "highWatermark must be >= lowWatermark")

    /**
     * Create [[WatermarkRequestStrategy]] with `lowWatermark` as half of
     * the specified `highWatermark`.
     */
    def this(highWatermark: Int) = this(highWatermark, lowWatermark = math.max(1, highWatermark / 2))

    /**
     * Invoked after each incoming message to determine how many more elements to request from the stream.
     *
     * @param remainingRequested current remaining number of elements that
     *   have been requested from upstream but not received yet
     * @return demand of more elements from the stream, returning 0 means that no
     *   more elements will be requested for now
     */
    def requestDemand(remainingRequested: Int): Int =
      if (remainingRequested < lowWatermark)
        highWatermark - remainingRequested
      else 0
  }
}

/**
 * INTERNAL API: Actual operator implementation backing [[SourceRef]]s.
 *
 * If initialPartnerRef is set, then the remote side is already set up.
 * If it is none, then we are the side creating the ref.
 */
@InternalApi
private[stream] final class SourceRefStageImpl[Out](val initialPartnerRef: OptionVal[ActorRef])
    extends GraphStageWithMaterializedValue[SourceShape[Out], SinkRef[Out]] { stage =>
  import SourceRefStageImpl.ActorRefStage
  import SourceRefStageImpl.WatermarkRequestStrategy

  val out: Outlet[Out] = Outlet[Out](s"${Logging.simpleName(getClass)}.out")
  override def shape = SourceShape.of(out)

  private def initialRefName =
    initialPartnerRef match {
      case OptionVal.Some(ref) => ref.toString
      case _                   => "<no-initial-ref>"
    }

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, SinkRef[Out]) =
    throw new IllegalStateException("Not supported")

  private[akka] override def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes,
      eagerMaterializer: Materializer): (GraphStageLogic, SinkRef[Out]) = {

    val logic = new TimerGraphStageLogic(shape) with StageLogging with ActorRefStage with OutHandler {
      override protected def logSource: Class[_] = classOf[SourceRefStageImpl[_]]

      private[this] val streamRefsMaster = StreamRefsMaster(eagerMaterializer.system)

      // settings ---
      import StreamRefAttributes._
      @silent("deprecated") // can't remove this settings access without breaking compat
      private[this] val settings = eagerMaterializer.settings.streamRefSettings

      @silent("deprecated") // can't remove this settings access without breaking compat
      private[this] val subscriptionTimeout = inheritedAttributes.get[StreamRefAttributes.SubscriptionTimeout](
        SubscriptionTimeout(settings.subscriptionTimeout))

      @silent("deprecated") // can't remove this settings access without breaking compat
      private[this] val bufferCapacity = inheritedAttributes
        .get[StreamRefAttributes.BufferCapacity](StreamRefAttributes.BufferCapacity(settings.bufferCapacity))
        .capacity

      @silent("deprecated") // can't remove this settings access without breaking compat
      private[this] val demandRedeliveryInterval = inheritedAttributes
        .get[StreamRefAttributes.DemandRedeliveryInterval](DemandRedeliveryInterval(settings.demandRedeliveryInterval))
        .timeout

      @silent("deprecated") // can't remove this settings access without breaking compat
      private[this] val finalTerminationSignalDeadline =
        inheritedAttributes
          .get[StreamRefAttributes.FinalTerminationSignalDeadline](
            FinalTerminationSignalDeadline(settings.finalTerminationSignalDeadline))
          .timeout
      // end of settings ---

      override protected val stageActorName: String = streamRefsMaster.nextSourceRefStageName()
      private[this] val self: GraphStageLogic.StageActor =
        getEagerStageActor(eagerMaterializer, poisonPillCompatibility = false)(initialReceive)
      override val ref: ActorRef = self.ref
      private[this] implicit def selfSender: ActorRef = ref

      val SubscriptionTimeoutTimerKey = "SubscriptionTimeoutKey"
      val DemandRedeliveryTimerKey = "DemandRedeliveryTimerKey"
      val TerminationDeadlineTimerKey = "TerminationDeadlineTimerKey"

      // demand management ---
      private var completed = false

      private var expectingSeqNr: Long = 0L
      private var localCumulativeDemand: Long = 0L
      private var localRemainingRequested: Int = 0

      private val receiveBuffer = FixedSizeBuffer[Out](bufferCapacity)

      private val requestStrategy: WatermarkRequestStrategy = WatermarkRequestStrategy(
        highWatermark = receiveBuffer.capacity)
      // end of demand management ---

      // initialized with the originRef if present, that means we're the "remote" for an already active Source on the other side (the "origin")
      // null otherwise, in which case we allocated first -- we are the "origin", and awaiting the other side to start when we'll receive this ref
      private var partnerRef: OptionVal[ActorRef] = OptionVal.None
      private def getPartnerRef = partnerRef.get

      override def preStart(): Unit = {
        log.debug("[{}] Allocated receiver: {}", stageActorName, self.ref)
        if (initialPartnerRef.isDefined) // this will set the partnerRef
          observeAndValidateSender(
            initialPartnerRef.get,
            "Illegal initialPartnerRef! This would be a bug in the SourceRef usage or impl.")

        // This timer will be cancelled if we receive the handshake from the remote SinkRef
        // either created in this method and provided as self.ref as initialPartnerRef
        // or as the response to first CumulativeDemand request sent to remote SinkRef
        scheduleOnce(SubscriptionTimeoutTimerKey, subscriptionTimeout.timeout)
      }

      override def onPull(): Unit = {
        tryPush()
        triggerCumulativeDemand()
      }

      def triggerCumulativeDemand(): Unit = {
        val i = receiveBuffer.remainingCapacity - localRemainingRequested
        if (partnerRef.isDefined && i > 0) {
          val addDemand = requestStrategy.requestDemand(receiveBuffer.used + localRemainingRequested)
          // only if demand has increased we shoot it right away
          // otherwise it's the same demand level, so it'd be triggered via redelivery anyway
          if (addDemand > 0) {
            localCumulativeDemand += addDemand
            localRemainingRequested += addDemand
            val demand = StreamRefsProtocol.CumulativeDemand(localCumulativeDemand)

            log.debug("[{}] Demanding until [{}] (+{})", stageActorName, localCumulativeDemand, addDemand)
            getPartnerRef ! demand
            scheduleDemandRedelivery()
          }
        }
      }

      def scheduleDemandRedelivery(): Unit =
        scheduleOnce(DemandRedeliveryTimerKey, demandRedeliveryInterval)

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case SubscriptionTimeoutTimerKey =>
          val ex = StreamRefSubscriptionTimeoutException(
            // we know the future has been competed by now, since it is in preStart
            s"[$stageActorName] Remote side did not subscribe (materialize) handed out Sink reference [$ref]," +
            s"within subscription timeout: ${PrettyDuration.format(subscriptionTimeout.timeout)}!")

          throw ex // this will also log the exception, unlike failStage; this should fail rarely, but would be good to have it "loud"

        case DemandRedeliveryTimerKey =>
          log.debug("[{}] Scheduled re-delivery of demand until [{}]", stageActorName, localCumulativeDemand)
          getPartnerRef ! StreamRefsProtocol.CumulativeDemand(localCumulativeDemand)
          scheduleDemandRedelivery()

        case TerminationDeadlineTimerKey =>
          failStage(RemoteStreamRefActorTerminatedException(
            s"Remote partner [$partnerRef] has terminated unexpectedly and no clean completion/failure message was received " +
            "(possible reasons: network partition or subscription timeout triggered termination of partner). Tearing down."))
      }

      def initialReceive: ((ActorRef, Any)) => Unit = {
        case (sender, msg @ StreamRefsProtocol.OnSubscribeHandshake(remoteRef)) =>
          cancelTimer(SubscriptionTimeoutTimerKey)
          observeAndValidateSender(remoteRef, "Illegal sender in SequencedOnNext")
          log.debug("[{}] Received handshake {} from {}", stageActorName, msg, sender)

          triggerCumulativeDemand()

        case (sender, msg @ StreamRefsProtocol.SequencedOnNext(seqNr, payload: Out @unchecked)) =>
          observeAndValidateSender(sender, "Illegal sender in SequencedOnNext")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in SequencedOnNext")
          log.debug("[{}] Received seq {} from {}", stageActorName, msg, sender)

          onReceiveElement(payload)
          triggerCumulativeDemand()

        case (sender, StreamRefsProtocol.RemoteStreamCompleted(seqNr)) =>
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkCompleted")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in RemoteSinkCompleted")
          log.debug("[{}] The remote stream has completed, completing as well...", stageActorName)

          self.unwatch(sender)
          completed = true
          tryPush()

        case (sender, StreamRefsProtocol.RemoteStreamFailure(reason)) =>
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkFailure")
          log.warning("[{}] The remote stream has failed, failing (reason: {})", stageActorName, reason)

          self.unwatch(sender)
          failStage(RemoteStreamRefActorTerminatedException(s"Remote stream (${sender.path}) failed, reason: $reason"))

        case (_, Terminated(p)) =>
          partnerRef match {
            case OptionVal.Some(`p`) =>
              // we need to start a delayed shutdown in case we were network partitioned and the final signal complete/fail
              // will never reach us; so after the given timeout we need to forcefully terminate this side of the stream ref
              // the other (sending) side terminates by default once it gets a Terminated signal so no special handling is needed there.
              scheduleOnce(TerminationDeadlineTimerKey, finalTerminationSignalDeadline)

            case _ =>
              // this should not have happened! It should be impossible that we watched some other actor
              failStage(
                RemoteStreamRefActorTerminatedException(
                  s"Received UNEXPECTED Terminated($p) message! " +
                  s"This actor was NOT our trusted remote partner, which was: $getPartnerRef. Tearing down."))
          }

        case (_, _) => // keep the compiler happy (stage actor receive is total)
      }

      def tryPush(): Unit =
        if (receiveBuffer.nonEmpty && isAvailable(out)) {
          val element = receiveBuffer.dequeue()
          push(out, element)
        } else if (receiveBuffer.isEmpty && completed) completeStage()

      private def onReceiveElement(payload: Out): Unit = {
        localRemainingRequested -= 1
        if (receiveBuffer.isEmpty && isAvailable(out)) {
          push(out, payload)
        } else if (receiveBuffer.isFull) {
          throw new IllegalStateException(
            s"Attempted to overflow buffer! " +
            s"Capacity: ${receiveBuffer.capacity}, incoming element: $payload, " +
            s"localRemainingRequested: $localRemainingRequested, localCumulativeDemand: $localCumulativeDemand")
        } else {
          receiveBuffer.enqueue(payload)
        }
      }

      /** @throws InvalidPartnerActorException when partner ref is invalid */
      def observeAndValidateSender(partner: ActorRef, msg: String): Unit =
        partnerRef match {
          case OptionVal.None =>
            log.debug("Received first message from {}, assuming it to be the remote partner for this stage", partner)
            partnerRef = OptionVal(partner)
            self.watch(partner)

          case OptionVal.Some(p) =>
            if (partner != p) {
              val ex = InvalidPartnerActorException(partner, getPartnerRef, msg)
              partner ! StreamRefsProtocol.RemoteStreamFailure(ex.getMessage)
              throw ex
            } // else, ref is valid and we don't need to do anything with it
        }

      /** @throws InvalidSequenceNumberException when sequence number is invalid */
      def observeAndValidateSequenceNr(seqNr: Long, msg: String): Unit =
        if (isInvalidSequenceNr(seqNr)) {
          throw InvalidSequenceNumberException(expectingSeqNr, seqNr, msg)
        } else {
          expectingSeqNr += 1
        }
      def isInvalidSequenceNr(seqNr: Long): Boolean =
        seqNr != expectingSeqNr

      setHandler(out, this)
    }
    (logic, SinkRefImpl(logic.ref))
  }

  override def toString: String =
    s"${Logging.simpleName(getClass)}($initialRefName)}"
}
