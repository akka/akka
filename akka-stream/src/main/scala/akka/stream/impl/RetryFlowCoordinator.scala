/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.pattern.BackoffSupervisor
import akka.stream.impl.RetryFlowCoordinator.InternalState
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic }

import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi private[akka] object RetryFlowCoordinator {
  class InternalState(val numberOfRestarts: Int, val retryDeadline: Long)
  case object InternalState {
    def apply(): InternalState = new InternalState(0, System.currentTimeMillis())
  }
  case object RetryTimer
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class RetryFlowCoordinator[In, State, Out](
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

        override def onDownstreamFinish(cause: Throwable): Unit = {
          // waiting for the failure from the upstream
          setKeepGoing(true)
        }
      })

    private def smallestRetryDelay(): FiniteDuration =
      (queueRetries.head._3.retryDeadline - System.currentTimeMillis()).millis

    private def scheduleRetryTimer() =
      scheduleOnce(RetryTimer, smallestRetryDelay())
  }
}
