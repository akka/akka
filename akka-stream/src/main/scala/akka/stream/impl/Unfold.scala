/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.japi.{ function, Pair }
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import java.util.Optional
import java.util.concurrent.CompletionStage
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Unfold[S, E](s: S, f: S => Option[(S, E)]) extends GraphStage[SourceShape[E]] {
  val out: Outlet[E] = Outlet("Unfold.out")
  override val shape: SourceShape[E] = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfold
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private[this] var state = s

      def onPull(): Unit = f(state) match {
        case Some((newState, v)) => {
          push(out, v)
          state = newState
        }
        case None => complete(out)
      }

      setHandler(out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldAsync[S, E](s: S, f: S => Future[Option[(S, E)]])
    extends GraphStage[SourceShape[E]] {
  val out: Outlet[E] = Outlet("UnfoldAsync.out")
  override val shape: SourceShape[E] = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldAsync
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private[this] var state = s
      private[this] var asyncHandler: Try[Option[(S, E)]] => Unit = _

      override def preStart(): Unit = {
        val ac = getAsyncCallback[Try[Option[(S, E)]]] {
          case Success(Some((newS, elem))) =>
            push(out, elem)
            state = newS
          case Success(None) => complete(out)
          case Failure(ex)   => fail(out, ex)
        }
        asyncHandler = ac.invoke
      }

      def onPull(): Unit = f(state).onComplete(asyncHandler)(akka.dispatch.ExecutionContexts.parasitic)

      setHandler(out, this)
    }
}

/**
 * [[UnfoldAsync]] optimized specifically for Java API and `CompletionStage`
 *
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldAsyncJava[S, E](
    s: S,
    f: function.Function[S, CompletionStage[Optional[Pair[S, E]]]])
    extends GraphStage[SourceShape[E]] {
  val out: Outlet[E] = Outlet("UnfoldAsync.out")
  override val shape: SourceShape[E] = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldAsync
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private[this] var state = s
      private[this] var asyncHandler: Try[Optional[Pair[S, E]]] => Unit = _

      override def preStart(): Unit = {
        val ac = getAsyncCallback[Try[Optional[Pair[S, E]]]] {
          case Success(maybeValue) =>
            if (maybeValue.isPresent) {
              val pair = maybeValue.get()
              push(out, pair.second)
              state = pair.first
            } else {
              complete(out)
            }
          case Failure(ex) => fail(out, ex)
        }
        asyncHandler = ac.invoke
      }

      def onPull(): Unit =
        f(state).handle((r, ex) => {
          if (ex != null) {
            asyncHandler(Failure(ex))
          } else {
            asyncHandler(Success(r))
          }
          null
        })
      setHandler(out, this)
    }
}
