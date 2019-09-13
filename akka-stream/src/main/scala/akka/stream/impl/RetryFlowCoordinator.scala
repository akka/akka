/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.pattern.BackoffSupervisor
import akka.stream.impl.RetryFlowCoordinator.{ RetryElement, RetryResult, RetryState, RetryTimer }
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic }

import scala.collection.immutable
import scala.collection.immutable.{ Queue, SortedSet }
import scala.concurrent.duration._
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi private[akka] object RetryFlowCoordinator {
  final class RetryState(val numberOfRestarts: Int = 0, val retryDeadline: Long) {
    override def toString: String = s"RetryState(numberOfRestarts=$numberOfRestarts,retryDeadline=$retryDeadline)"
  }
  final class RetryElement[In, State](val in: In, val userState: State, val internalState: RetryState) {
    override def toString: String = s"RetryElement($internalState)"
  }
  final class RetryResult[In, Out, UserState](
      val tried: RetryElement[In, UserState],
      val out: Try[Out],
      val state: UserState) {
    override def toString: String = s"RetryResult(${out.getClass.getSimpleName})"
  }
  case object RetryTimer
}

/**
 * INTERNAL API.
 *
 * external in -- ----> internal out ... origFlow ... internalIn (retryWith)
 * (InData, UserCtx) |                                               None -> externalOut
 *                  ------------------------------------------- Some(s)
 *
 *
 */
@InternalApi private[akka] class RetryFlowCoordinator[InData, UserCtx, OutData](
    outBufferSize: Int,
    retryWith: (InData, Try[OutData], UserCtx) => Option[(InData, UserCtx)],
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double)
    extends GraphStage[BidiShape[
      (InData, UserCtx),
      RetryElement[InData, UserCtx],
      RetryResult[InData, OutData, UserCtx],
      (Try[OutData], UserCtx)]] {

  private type In = (InData, UserCtx)
  private type Out = (Try[OutData], UserCtx)

  private val externalIn = Inlet[(InData, UserCtx)]("RetryFlow.externalIn")
  private val externalOut = Outlet[(Try[OutData], UserCtx)]("RetryFlow.externalOut")

  private val internalOut = Outlet[RetryElement[InData, UserCtx]]("RetryFlow.internalOut")
  private val internalIn = Inlet[RetryResult[InData, OutData, UserCtx]]("RetryFlow.internalIn")

  override val shape: BidiShape[In, RetryElement[InData, UserCtx], RetryResult[InData, OutData, UserCtx], Out] =
    BidiShape(externalIn, internalOut, internalIn, externalOut)

  override def createLogic(attributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    private final val nanoTimeOffset = System.nanoTime()
    private var numElementsInCycle = 0
    private var queueRetries: Option[RetryElement[InData, UserCtx]] = None
    private val queueOut = scala.collection.mutable.Queue.empty[(Try[OutData], UserCtx)]

    setHandler(
      externalIn,
      new InHandler {
        override def onPush(): Unit = {
          val element: RetryElement[InData, UserCtx] = {
            val (in, userState) = grab(externalIn)
            new RetryElement(in, userState, new RetryState(retryDeadline = System.nanoTime() - nanoTimeOffset))
          }
//          if (isAvailable(internalOut)) { // TODO Could we get push without internal demand? Yes, while retrying.
          push(internalOut, element)
          numElementsInCycle += 1
//          } else {
//            queueRetries += element
//          }
        }

        override def onUpstreamFinish(): Unit =
          if (numElementsInCycle == 0 && !retriesRequired) { // TODO what happens with the upstream finish?
            emitMultiple(externalOut, queueOut.iterator, () => completeStage())
          }
      })

    setHandler(
      internalOut,
      new OutHandler {
        override def onPull(): Unit = {
          // internal demand
          if (retriesRequired) {
            scheduleRetryTimer()
          } else {
            if (!hasBeenPulled(externalIn) && !isClosed(externalIn)) {
              pull(externalIn)
            }
          }
        }

        override def onDownstreamFinish(cause: Throwable): Unit = {
          // waiting for the failure from the upstream
          setKeepGoing(true)
        }
      })

    setHandler(
      internalIn,
      new InHandler {
        override def onPush(): Unit = {
          numElementsInCycle -= 1
          val result = grab(internalIn)
          println(s"internalIn: result $result")
          val shouldRetryWith =
            retryWith.apply(result.tried.in, result.out, result.state)
          println(s"internalIn: shouldRetryWith $shouldRetryWith")
          shouldRetryWith match {
            case None => pushAndCompleteIfLast((result.out, result.state))
            case Some(xs) =>
              val current = System.nanoTime() - nanoTimeOffset
              queueRetries = Some {
                val numRestarts = result.tried.internalState.numberOfRestarts + 1
                val delay = BackoffSupervisor.calculateDelay(numRestarts, minBackoff, maxBackoff, randomFactor)
                new RetryElement(xs._1, xs._2, new RetryState(numRestarts, current + delay.toNanos))
              }

              // TODO why? This emit elements if Try is Success, but still retries with the elements in `xs`
              result.out.foreach { _ =>
                pushAndCompleteIfLast((result.out, result.state))
              }

              if (retriesRequired) {
                pull(internalIn)
                scheduleRetryTimer()
              } else {
                if (isClosed(externalIn) && queueOut.isEmpty) completeStage()
                else pull(internalIn)
              }
          }
        }
      })

    setHandler(
      externalOut,
      new OutHandler {
        override def onPull(): Unit =
          // external demand
          if (queueOut.nonEmpty) {
            push(externalOut, queueOut.dequeue())
          } else {
            if (!hasBeenPulled(internalIn)) pull(internalIn)
          }
      })

    override def onTimer(timerKey: Any): Unit = timerKey match {
      case RetryTimer =>
        if (isAvailable(internalOut)) { // TODO always true?
          push(internalOut, queueRetries.get)
          queueRetries = None
          numElementsInCycle += 1
        }
    }

    private def pushAndCompleteIfLast(elem: (Try[OutData], UserCtx)): Unit = {
      println(s"pushAndCompleteIfLast $elem")
      if (isAvailable(externalOut) && queueOut.isEmpty) {
        push(externalOut, elem)
      } else if (queueOut.size + 1 > outBufferSize) {
        failStage(new IllegalStateException(s"Buffer limit of $outBufferSize has been exceeded"))
      } else {
        queueOut.enqueue(elem)
      }

      if (isClosed(externalIn) && !retriesRequired && numElementsInCycle == 0 && queueOut.isEmpty) {
        completeStage()
      }
    }

    private def retriesRequired: Boolean = {
      println(s"retriesRequired: $queueRetries")
      queueRetries.nonEmpty
    }

    private def smallestRetryDelay: Long =
      queueRetries.head.internalState.retryDeadline - (System.nanoTime - nanoTimeOffset) // TODO can this become negative?

    private def scheduleRetryTimer(): Unit =
      scheduleOnce(RetryTimer, smallestRetryDelay.nanos)
  }
}
