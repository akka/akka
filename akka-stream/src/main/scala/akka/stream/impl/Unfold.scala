/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.stage.{ OutHandler, GraphStageLogic, GraphStage }
import akka.stream._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/**
 * Unfold `GraphStage` class
 * @param s initial state
 * @param f unfold function
 * @tparam S state
 * @tparam E element
 */
private[akka] class Unfold[S, E](s: S, f: S ⇒ Option[(S, E)]) extends GraphStage[SourceShape[E]] {

  val out: Outlet[E] = Outlet("Unfold")

  override val shape: SourceShape[E] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {
      private[this] var state = s

      setHandler(out, new OutHandler {
        override def onPull(): Unit = f(state) match {
          case None ⇒ complete(out)
          case Some((newState, v)) ⇒ {
            push(out, v)
            state = newState
          }
        }
      })
    }
  }
}

/**
 * UnfoldAsync `GraphStage` class
 * @param s initial state
 * @param f unfold function
 * @tparam S state
 * @tparam E element
 */
private[akka] class UnfoldAsync[S, E](s: S, f: S ⇒ Future[Option[(S, E)]]) extends GraphStage[SourceShape[E]] {

  val out: Outlet[E] = Outlet("UnfoldAsync")

  override val shape: SourceShape[E] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {
      private[this] var state = s

      private[this] var asyncHandler: Function1[Try[Option[(S, E)]], Unit] = _

      override def preStart() = {
        val ac = getAsyncCallback[Try[Option[(S, E)]]] {
          case Failure(ex)   ⇒ fail(out, ex)
          case Success(None) ⇒ complete(out)
          case Success(Some((newS, elem))) ⇒ {
            push(out, elem)
            state = newS
          }
        }
        asyncHandler = ac.invoke
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          f(state).onComplete(asyncHandler)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
      })
    }
  }
}
