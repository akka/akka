/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.dispatch.ExecutionContexts
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.Supervision
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 * In general, passes through incoming futures. However, it keeps track of the status of Futures passing through and
 * applies backpressure when the number of outstanding Futures reaches the value of the `parallelism` setting.
 *
 * If a newly or previously processed Future finally fails, this stage with also fail if the SupervisionStrategy is Stop.
 * Otherwise, it will just ignore the error.
 *
 * @param parallelism The maximum number of outstanding futures gone through this stage.
 */
private[stream] class LimitUncompleted[T](parallelism: Int) extends GraphStage[FlowShape[Future[T], Future[T]]] {
  require(parallelism > 0)

  val in = Inlet[Future[T]]("LimitUncompleted.in")
  val out = Outlet[Future[T]]("LimitUncompleted.out")
  def shape: FlowShape[Future[T], Future[T]] = FlowShape(in, out)
  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      setHandlers(in, out, this)

      private lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
      var inFlight: Long = 0L

      val callback = getAsyncCallback[Try[T]](handleOutstandingResult)

      override def onPush(): Unit = {
        val ele = grab(in)

        ele.value match {
          case Some(Success(_))  => push(out, ele)
          case Some(Failure(ex)) => handleFailure(ex)
          case None =>
            inFlight += 1
            ele.onComplete(callback.invoke)(ExecutionContexts.sameThreadExecutionContext)
            push(out, ele)
        }

        checkStatus()
      }
      override def onPull(): Unit = checkStatus()
      override def onUpstreamFinish(): Unit =
        if (inFlight == 0L) completeStage()
      // else we need to wait for all to complete so that a failing element could still fail the stage

      private def handleOutstandingResult(result: Try[T]): Unit = {
        inFlight -= 1
        result.failed.foreach(handleFailure)
        checkStatus()
      }

      private def handleFailure(ex: Throwable): Unit =
        decider(ex) match {
          case Supervision.Stop => failStage(ex)
          case _                => // skip further handling
        }

      private def checkStatus(): Unit =
        if (inFlight == 0 && isClosed(in)) completeStage()
        else if (inFlight < parallelism && !hasBeenPulled(in) && isAvailable(out)) tryPull(in)
    }
}

/**
 * Unwraps futures by waiting until an incoming future is completed and passes on successful results. If a future fails
 * and the SupervisionStrategy decides to Stop, the stage will fail with the given failure, otherwise it will ignore the
 * failure and skip the element.
 */
class WaitForCompletion[T] extends GraphStage[FlowShape[Future[T], T]] {
  val in = Inlet[Future[T]]("WaitForCompletion.in")
  val out = Outlet[T]("WaitForCompletion.out")
  def shape: FlowShape[Future[T], T] = FlowShape(in, out)
  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      setHandlers(in, out, this)

      private lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
      private var inFlight: Boolean = false
      private val callback = getAsyncCallback[Try[T]](handleResult)

      override def onPush(): Unit = {
        val ele = grab(in)
        ele.value match {
          case Some(result) =>
            handleResult(result)
          case None =>
            require(!inFlight)
            inFlight = true
            ele.onComplete(callback.invoke)(ExecutionContexts.sameThreadExecutionContext)
        }
      }

      override def onPull(): Unit = pull(in)

      private def handleResult(result: Try[T]): Unit = {
        inFlight = false
        result match {
          case Success(null) => // ignore
          case Success(t)    => push(out, t)
          case Failure(ex) =>
            decider(ex) match {
              case Supervision.Stop => failStage(ex)
              case _                => // throw result away
            }
        }
        if (isClosed(in)) completeStage()
        else if (isAvailable(out) && !hasBeenPulled(in)) pull(in)
      }

      override def onUpstreamFinish(): Unit =
        if (!inFlight) completeStage()
    }
}
