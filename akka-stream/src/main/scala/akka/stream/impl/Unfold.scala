/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }
import akka.stream._

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Unfold[S, E](s: S, f: S ⇒ Option[(S, E)]) extends GraphStage[SourceShape[E]] {
  val out: Outlet[E] = Outlet("Unfold.out")
  override val shape: SourceShape[E] = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfold
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private[this] var state = s

      def onPull(): Unit = f(state) match {
        case None ⇒ complete(out)
        case Some((newState, v)) ⇒ {
          push(out, v)
          state = newState
        }
      }

      setHandler(out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldAsync[S, E](s: S, f: S ⇒ Future[Option[(S, E)]]) extends GraphStage[SourceShape[E]] {
  val out: Outlet[E] = Outlet("UnfoldAsync.out")
  override val shape: SourceShape[E] = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldAsync
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private[this] var state = s
      private[this] var asyncHandler: Function1[Try[Option[(S, E)]], Unit] = _

      override def preStart() = {
        val ac = getAsyncCallback[Try[Option[(S, E)]]] {
          case Failure(ex)   ⇒ fail(out, ex)
          case Success(None) ⇒ complete(out)
          case Success(Some((newS, elem))) ⇒
            push(out, elem)
            state = newS
        }
        asyncHandler = ac.invoke
      }

      def onPull(): Unit = f(state).onComplete(asyncHandler)(
        akka.dispatch.ExecutionContexts.sameThreadExecutionContext)

      setHandler(out, this)
    }
}
