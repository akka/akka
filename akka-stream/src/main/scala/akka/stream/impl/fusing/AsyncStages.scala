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

class LimitUncompleted[T](parallelism: Int) extends GraphStage[FlowShape[Future[T], Future[T]]] {
  require(parallelism > 0)

  val in = Inlet[Future[T]]("LimitUncompleted.in")
  val out = Outlet[Future[T]]("LimitUncompleted.out")
  def shape: FlowShape[Future[T], Future[T]] = FlowShape(in, out)
  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
      setHandlers(in, out, this)

      var inFlight: Long = 0L

      val callback = getAsyncCallback[Try[T]] { result =>
        inFlight -= 1
        handleResult(result, () => ())
        pullIfNecessary()
      }

      override def onPush(): Unit = {
        val ele = grab(in)

        if (ele.isCompleted)
          handleResult(ele.value.get, () => push(out, ele))
        else {
          inFlight += 1
          ele.onComplete(callback.invoke)(ExecutionContexts.sameThreadExecutionContext)
          push(out, ele)
        }

        pullIfNecessary()
      }
      override def onPull(): Unit = pullIfNecessary()

      private def handleResult(result: Try[T], whenSuccess: () => Unit): Unit =
        // result handling only needed to be able to fail fast when futures fail
        result match {
          case Success(_) => whenSuccess()
          case Failure(ex) =>
            decider(ex) match {
              case Supervision.Stop => failStage(ex)
              case _                => // skip further handling
            }
        }

      private def pullIfNecessary(): Unit =
        if (inFlight < parallelism && !hasBeenPulled(in) && isAvailable(out)) tryPull(in)
    }
}

class WaitForCompletion[T] extends GraphStage[FlowShape[Future[T], T]] {
  val in = Inlet[Future[T]]("WaitForCompletion.in")
  val out = Outlet[T]("WaitForCompletion.out")
  def shape: FlowShape[Future[T], T] = FlowShape(in, out)
  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      setHandlers(in, out, this)
      var inFlight: Boolean = false

      val callback = getAsyncCallback[Try[T]](handleResult)

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
