/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.pattern.BackoffSupervisor
import akka.stream.scaladsl.RetryFlowCoordinator.InternalState
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic }
import akka.stream.{ Attributes, BidiShape, FlowShape, Inlet, Outlet }

import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.Try

object RetryFlow {

  /**
   * Allows retrying individual elements in the stream with exponential backoff.
   *
   * The retry condition is controlled by the `retryWith` partial function. It takes an output element of the wrapped
   * flow and should return one or more requests to be retried. For example:
   *
   * case Failure(_) => Some(List(..., ..., ...)) - for every failed response will issue three requests to retry
   *
   * case Failure(_) => Some(Nil) - every failed response will be ignored
   *
   * case Failure(_) => None - every failed response will be propagated downstream
   *
   * case Success(_) => Some(List(...)) - for every successful response a single retry will be issued
   *
   * A successful or failed response will be propagated downstream if it is not matched by the `retryFlow` function.
   *
   * If a successful response is matched and issued a retry, the response is still propagated downstream.
   *
   * The implementation of the RetryFlow assumes that `flow` follows one-in-out-out element semantics.
   *
   * The wrapped `flow` and `retryWith` takes an additional `State` parameter which can be used to correlate a request
   * with a response.
   *
   * Backoff state is tracked separately per-element coming into the wrapped `flow`.
   *
   * @param parallelism controls the number of in-flight requests in the wrapped flow
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow to retry elements from
   * @param retryWith retry condition decision partial function
   */
  def withBackoff[In, Out, State, Mat](
      parallelism: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      flow: Flow[(In, State), (Try[Out], State), Mat])(
      retryWith: PartialFunction[(Try[Out], State), Option[immutable.Iterable[(In, State)]]])
      : Flow[(In, State), (Try[Out], State), Mat] =
    withBackoffAndContext(parallelism, minBackoff, maxBackoff, randomFactor, FlowWithContext.fromTuples(flow))(
      retryWith).asFlow

  /**
   * Allows retrying individual elements in the stream with exponential backoff.
   *
   * The retry condition is controlled by the `retryWith` partial function. It takes an output element of the wrapped
   * flow and should return one or more requests to be retried. For example:
   *
   * case Failure(_) => Some(List(..., ..., ...)) - for every failed response will issue three requests to retry
   *
   * case Failure(_) => Some(Nil) - every failed response will be ignored
   *
   * case Failure(_) => None - every failed response will be propagated downstream
   *
   * case Success(_) => Some(List(...)) - for every successful response a single retry will be issued
   *
   * A successful or failed response will be propagated downstream if it is not matched by the `retryFlow` function.
   *
   * If a successful response is matched and issued a retry, the response is still propagated downstream.
   *
   * The implementation of the RetryFlow assumes that `flow` follows one-in-out-out element semantics.
   *
   * The wrapped `flow` and `retryWith` takes an additional `State` parameter which can be used to correlate a request
   * with a response.
   *
   * Backoff state is tracked separately per-element coming into the wrapped `flow`.
   *
   * @param parallelism controls the number of in-flight requests in the wrapped flow
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow with context to retry elements from
   * @param retryWith retry condition decision partial function
   */
  def withBackoffAndContext[In, Out, State, Mat](
      parallelism: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      flow: FlowWithContext[In, State, Try[Out], State, Mat])(
      retryWith: PartialFunction[(Try[Out], State), Option[immutable.Iterable[(In, State)]]])
      : FlowWithContext[In, State, Try[Out], State, Mat] =
    FlowWithContext.fromTuples {
      Flow.fromGraph {
        GraphDSL.create(flow) { implicit b => origFlow =>
          import GraphDSL.Implicits._

          val retry =
            b.add(
              new RetryFlowCoordinator[In, State, Out](parallelism, retryWith, minBackoff, maxBackoff, randomFactor))
          val broadcast = b.add(new Broadcast[(In, State, InternalState)](outputPorts = 2, eagerCancel = true))
          val zip =
            b.add(new ZipWith2[(Try[Out], State), InternalState, (Try[Out], State, InternalState)]((el, state) =>
              (el._1, el._2, state)))

          retry.out2 ~> broadcast.in

          broadcast.out(0).map(msg => (msg._1, msg._2)) ~> origFlow ~> zip.in0
          broadcast.out(1).map(msg => msg._3) ~> zip.in1

          zip.out ~> retry.in2

          FlowShape(retry.in1, retry.out1)
        }
      }
    }
}

private object RetryFlowCoordinator {
  class InternalState(val numberOfRestarts: Int, val retryDeadline: Long)
  case object InternalState {
    def apply(): InternalState = new InternalState(0, System.currentTimeMillis())
  }
  case object RetryTimer
}

private class RetryFlowCoordinator[In, State, Out](
    parallelism: Long,
    retryWith: PartialFunction[(Try[Out], State), Option[immutable.Iterable[(In, State)]]],
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double)
    extends GraphStage[
      BidiShape[(In, State), (Try[Out], State), (Try[Out], State, InternalState), (In, State, InternalState)]] {

  private val externalIn = Inlet[(In, State)]("RetryFlow.externalIn")
  private val externalOut = Outlet[(Try[Out], State)]("RetryFlow.externalOut")

  private val internalIn = Inlet[(Try[Out], State, InternalState)]("RetryFlow.internalIn")
  private val internalOut = Outlet[(In, State, InternalState)]("RetryFlow.internalOut")

  override val shape
      : BidiShape[(In, State), (Try[Out], State), (Try[Out], State, InternalState), (In, State, InternalState)] =
    BidiShape(externalIn, externalOut, internalIn, internalOut)

  override def createLogic(attributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    private var numElementsInCycle = 0
    private var queueRetries =
      scala.collection.immutable.SortedSet.empty[(In, State, InternalState)](Ordering.fromLessThan { (e1, e2) =>
        if (e1._3.retryDeadline != e2._3.retryDeadline) e1._3.retryDeadline < e2._3.retryDeadline
        else e1.hashCode < e2.hashCode
      })
    private val queueOut = scala.collection.mutable.Queue.empty[(Try[Out], State)]

    private final val RetryTimer = "retry_timer"

    setHandler(
      externalIn,
      new InHandler {
        override def onPush(): Unit = {
          val is = {
            val in = grab(externalIn)
            (in._1, in._2, InternalState())
          }
          if (isAvailable(internalOut)) {
            push(internalOut, is)
            numElementsInCycle += 1
          } else {
            queueRetries += is
          }
        }

        override def onUpstreamFinish(): Unit =
          if (numElementsInCycle == 0 && queueRetries.isEmpty) {
            if (queueOut.isEmpty) completeStage()
            else emitMultiple(externalOut, queueOut.iterator, () => completeStage())
          }
      })

    setHandler(
      externalOut,
      new OutHandler {
        override def onPull(): Unit =
          // prioritize pushing queued element if available
          if (queueOut.isEmpty) {
            if (!hasBeenPulled(internalIn)) pull(internalIn)
          } else push(externalOut, queueOut.dequeue())
      })

    setHandler(
      internalIn,
      new InHandler {
        override def onPush(): Unit = {
          numElementsInCycle -= 1
          grab(internalIn) match {
            case (out, s, internalState) =>
              retryWith
                .applyOrElse[(Try[Out], State), Option[immutable.Iterable[(In, State)]]]((out, s), _ => None)
                .fold(pushAndCompleteIfLast((out, s))) {
                  xs =>
                    val current = System.currentTimeMillis()
                    xs.foreach {
                      case (in, state) =>
                        val numRestarts = internalState.numberOfRestarts + 1
                        val delay = BackoffSupervisor.calculateDelay(numRestarts, minBackoff, maxBackoff, randomFactor)
                        queueRetries += ((in, state, new InternalState(numRestarts, current + delay.toMillis)))
                    }

                    out.foreach { _ =>
                      pushAndCompleteIfLast((out, s))
                    }

                    if (queueRetries.isEmpty) {
                      if (isClosed(externalIn) && queueOut.isEmpty) completeStage()
                      else pull(internalIn)
                    } else {
                      pull(internalIn)
                      scheduleRetryTimer()
                    }
                }
          }
        }
      })

    override def onTimer(timerKey: Any): Unit = timerKey match {
      case `RetryTimer` =>
        if (isAvailable(internalOut)) {
          val elem = queueRetries.head
          queueRetries = queueRetries.tail

          push(internalOut, elem)
          numElementsInCycle += 1
        }
    }

    private def pushAndCompleteIfLast(elem: (Try[Out], State)): Unit = {
      if (isAvailable(externalOut) && queueOut.isEmpty) {
        push(externalOut, elem)
      } else if (queueOut.size + 1 > parallelism) {
        failStage(new IllegalStateException(s"Buffer limit of $parallelism has been exceeded"))
      } else {
        queueOut.enqueue(elem)
      }

      if (isClosed(externalIn) && queueRetries.isEmpty && numElementsInCycle == 0 && queueOut.isEmpty) {
        completeStage()
      }
    }

    setHandler(
      internalOut,
      new OutHandler {
        override def onPull(): Unit =
          if (queueRetries.isEmpty) {
            if (!hasBeenPulled(externalIn) && !isClosed(externalIn)) {
              pull(externalIn)
            }
          } else {
            scheduleRetryTimer()
          }

        override def onDownstreamFinish(): Unit = {
          // waiting for the failure from the upstream
          setKeepGoing(true)
        }
      })

    private def smallestRetryDelay() =
      (queueRetries.head._3.retryDeadline - System.currentTimeMillis()).millis

    private def scheduleRetryTimer() = {
      cancelTimer(RetryTimer)
      scheduleOnce(RetryTimer, smallestRetryDelay())
    }
  }
}
