/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.streamref

import akka.Done
import akka.NotUsed
import akka.actor.{ ActorRef, Terminated }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl.Sink
import akka.stream.stage._
import akka.util.{ OptionVal, PrettyDuration }

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }

/** INTERNAL API: Implementation class, not intended to be touched directly by end-users */
@InternalApi
private[stream] final case class SinkRefImpl[In](initialPartnerRef: ActorRef) extends SinkRef[In] {
  override def sink(): Sink[In, NotUsed] =
    Sink.fromGraph(new SinkRefStageImpl[In](OptionVal.Some(initialPartnerRef))).mapMaterializedValue(_ => NotUsed)
}

/**
 * INTERNAL API: Actual operator implementation backing [[SinkRef]]s.
 *
 * If initialPartnerRef is set, then the remote side is already set up. If it is none, then we are the side creating
 * the ref.
 */
@InternalApi
private[stream] final class SinkRefStageImpl[In] private[akka] (val initialPartnerRef: OptionVal[ActorRef])
    extends GraphStageWithMaterializedValue[SinkShape[In], Future[SourceRef[In]]] {

  val in: Inlet[In] = Inlet[In](s"${Logging.simpleName(getClass)}($initialRefName).in")
  override def shape: SinkShape[In] = SinkShape.of(in)

  private def initialRefName: String =
    initialPartnerRef match {
      case OptionVal.Some(ref) => ref.toString
      case OptionVal.None      => "<no-initial-ref>"
    }

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[SourceRefImpl[In]]

    val logic = new TimerGraphStageLogic(shape) with StageLogging with InHandler {

      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)

      // settings ---
      import StreamRefAttributes._
      private[this] lazy val settings = ActorMaterializerHelper.downcast(materializer).settings.streamRefSettings

      private[this] lazy val subscriptionTimeout = inheritedAttributes.get[StreamRefAttributes.SubscriptionTimeout](
        SubscriptionTimeout(settings.subscriptionTimeout))
      // end of settings ---

      override protected lazy val stageActorName: String = streamRefsMaster.nextSinkRefStageName()
      private[this] var self: GraphStageLogic.StageActor = _
      implicit def selfSender: ActorRef = self.ref

      private var partnerRef: OptionVal[ActorRef] = OptionVal.None
      private def getPartnerRef: ActorRef =
        partnerRef match {
          case OptionVal.Some(ref) => ref
          case OptionVal.None      => throw TargetRefNotInitializedYetException()
        }

      val SubscriptionTimeoutTimerKey = "SubscriptionTimeoutKey"

      // demand management ---
      private var remoteCumulativeDemandReceived: Long = 0L
      private var remoteCumulativeDemandConsumed: Long = 0L
      // end of demand management ---

      private var completedBeforeRemoteConnected: OptionVal[Try[Done]] = OptionVal.None

      // Some when this side of the stream has completed/failed, and we await the Terminated() signal back from the partner
      // so we can safely shut down completely; This is to avoid *our* Terminated() signal to reach the partner before the
      // Complete/Fail message does, which can happen on transports such as Artery which use a dedicated lane for system messages (Terminated)
      private[this] var finishedWithAwaitingPartnerTermination: OptionVal[Try[Done]] = OptionVal.None

      override def preStart(): Unit = {
        self = getStageActor(initialReceive)

        initialPartnerRef match {
          case OptionVal.Some(ref) =>
            // this will set the `partnerRef`
            observeAndValidateSender(
              ref,
              "Illegal initialPartnerRef! This may be a bug, please report your " +
              "usage and complete stack trace on the issue tracker: https://github.com/akka/akka")
            tryPull()
          case OptionVal.None =>
            // only schedule timeout timer if partnerRef has not been resolved yet (i.e. if this instance of the Actor
            // has not been provided with a valid initialPartnerRef)
            scheduleOnce(SubscriptionTimeoutTimerKey, subscriptionTimeout.timeout)
        }

        log.debug(
          "Created SinkRef, pointing to remote Sink receiver: {}, local worker: {}",
          initialPartnerRef,
          self.ref)

        promise.success(SourceRefImpl(self.ref))
      }

      lazy val initialReceive: ((ActorRef, Any)) => Unit = {
        case (_, Terminated(ref)) =>
          if (ref == getPartnerRef)
            finishedWithAwaitingPartnerTermination match {
              case OptionVal.Some(Failure(ex)) =>
                failStage(ex)
              case OptionVal.Some(_ /* known to be Success*/ ) =>
                completeStage() // other side has terminated (in response to a completion message) so we can safely terminate
              case OptionVal.None =>
                failStage(
                  RemoteStreamRefActorTerminatedException(
                    s"Remote target receiver of data $partnerRef terminated. " +
                    s"Local stream terminating, message loss (on remote side) may have happened."))
            }

        case (sender, StreamRefsProtocol.CumulativeDemand(d)) =>
          // the other side may attempt to "double subscribe", which we want to fail eagerly since we're 1:1 pairings
          observeAndValidateSender(sender, "Illegal sender for CumulativeDemand")

          if (remoteCumulativeDemandReceived < d) {
            remoteCumulativeDemandReceived = d
            log.debug(
              "Received cumulative demand [{}], consumable demand: [{}]",
              StreamRefsProtocol.CumulativeDemand(d),
              remoteCumulativeDemandReceived - remoteCumulativeDemandConsumed)
          }

          tryPull()
      }

      override def onPush(): Unit = {
        val elem = grabSequenced(in)
        getPartnerRef ! elem
        log.debug("Sending sequenced: {} to {}", elem, getPartnerRef)
        tryPull()
      }

      private def tryPull(): Unit =
        if (remoteCumulativeDemandConsumed < remoteCumulativeDemandReceived && !hasBeenPulled(in)) {
          pull(in)
        }

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case SubscriptionTimeoutTimerKey =>
          val ex = StreamRefSubscriptionTimeoutException(
            // we know the future has been competed by now, since it is in preStart
            s"[$stageActorName] Remote side did not subscribe (materialize) handed out Source reference [${promise.future.value}], " +
            s"within subscription timeout: ${PrettyDuration.format(subscriptionTimeout.timeout)}!")

          throw ex
      }

      private def grabSequenced[T](in: Inlet[T]): StreamRefsProtocol.SequencedOnNext[T] = {
        val onNext = StreamRefsProtocol.SequencedOnNext(remoteCumulativeDemandConsumed, grab(in))
        remoteCumulativeDemandConsumed += 1
        onNext
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        partnerRef match {
          case OptionVal.Some(ref) =>
            ref ! StreamRefsProtocol.RemoteStreamFailure(ex.getMessage)
            finishedWithAwaitingPartnerTermination = OptionVal(Failure(ex))
            setKeepGoing(true) // we will terminate once partner ref has Terminated (to avoid racing Terminated with completion message)

          case _ =>
            completedBeforeRemoteConnected = OptionVal(scala.util.Failure(ex))
            // not terminating on purpose, since other side may subscribe still and then we want to fail it
            // the stage will be terminated either by timeout, or by the handling in `observeAndValidateSender`
            setKeepGoing(true)
        }
      }

      override def onUpstreamFinish(): Unit =
        partnerRef match {
          case OptionVal.Some(ref) =>
            ref ! StreamRefsProtocol.RemoteStreamCompleted(remoteCumulativeDemandConsumed)
            finishedWithAwaitingPartnerTermination = OptionVal(Success(Done))
            setKeepGoing(true) // we will terminate once partner ref has Terminated (to avoid racing Terminated with completion message)
          case _ =>
            completedBeforeRemoteConnected = OptionVal(scala.util.Success(Done))
            // not terminating on purpose, since other side may subscribe still and then we want to complete it
            setKeepGoing(true)
        }

      @throws[InvalidPartnerActorException]
      def observeAndValidateSender(partner: ActorRef, failureMsg: String): Unit = {
        if (partnerRef.isEmpty) {
          partnerRef = OptionVal(partner)
          partner ! StreamRefsProtocol.OnSubscribeHandshake(self.ref)
          cancelTimer(SubscriptionTimeoutTimerKey)
          self.watch(partner)

          completedBeforeRemoteConnected match {
            case OptionVal.Some(scala.util.Failure(ex)) =>
              log.warning(
                "Stream already terminated with exception before remote side materialized, sending failure: {}",
                ex)
              partner ! StreamRefsProtocol.RemoteStreamFailure(ex.getMessage)
              finishedWithAwaitingPartnerTermination = OptionVal(Failure(ex))
              setKeepGoing(true) // we will terminate once partner ref has Terminated (to avoid racing Terminated with completion message)

            case OptionVal.Some(scala.util.Success(Done)) =>
              log.warning("Stream already completed before remote side materialized, failing now.")
              partner ! StreamRefsProtocol.RemoteStreamCompleted(remoteCumulativeDemandConsumed)
              finishedWithAwaitingPartnerTermination = OptionVal(Success(Done))
              setKeepGoing(true) // we will terminate once partner ref has Terminated (to avoid racing Terminated with completion message)

            case OptionVal.None =>
              if (partner != getPartnerRef) {
                val ex = InvalidPartnerActorException(partner, getPartnerRef, failureMsg)
                partner ! StreamRefsProtocol.RemoteStreamFailure(ex.getMessage)
                throw ex
              } // else { ref is valid }
          }
        }
      }

      setHandler(in, this)
    }

    (logic, promise.future)
  }

  override def toString = s"${Logging.simpleName(getClass)}($initialRefName)"
}
