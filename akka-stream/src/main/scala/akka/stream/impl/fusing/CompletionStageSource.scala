/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future

import scala.util.Try

import akka.stream.Attributes
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler

private[akka] final class CompletionStageSource[T](stage: CompletionStage[T]) extends GraphStage[SourceShape[T]] {
  ReactiveStreamsCompliance.requireNonNullElement(stage)

  override def initialAttributes: Attributes = DefaultAttributes.completionStageSource
  private val out = Outlet[T]("CompletionStageSource.out")
  override val shape: SourceShape[T] = SourceShape(out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      override def onPull(): Unit = {
        val future = stage.toCompletableFuture
        if (future.isDone && !future.isCompletedExceptionally) {
          push(out, future.get())
        } else {
          val callback = getAsyncCallback(handle).invoke(_)
          stage.handle[Unit]((value, ex) => {
            if (ex != null) {
              callback(scala.util.Failure[T](ex))
            } else {
              callback(scala.util.Success(value))
            }
          })
        }

        setHandler(
          out,
          new OutHandler {
            override def onPull(): Unit = ()
            override def onDownstreamFinish(cause: Throwable): Unit = {
              super.onDownstreamFinish(cause)
              stage match {
                case future: Future[T @unchecked] =>
                  future.cancel(true)
                case _ =>
              }
            }
          })
      }

      private def handle(result: Try[T]): Unit = result match {
        case scala.util.Success(null) => completeStage()
        case scala.util.Success(v)    => emit(out, v, () => completeStage())
        case scala.util.Failure(t)    => failStage(t)
      }

      override def onDownstreamFinish(cause: Throwable): Unit = {
        super.onDownstreamFinish(cause)
        stage match {
          case future: Future[T @unchecked] =>
            future.cancel(true)
          case _ =>
        }
      }

      setHandler(out, this)
    }
}
