/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing
import scala.concurrent.Future
import scala.util.Try

import akka.dispatch.ExecutionContexts
import akka.stream.Attributes
import akka.stream.Attributes.SourceLocation
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler

private[akka] class LazyFutureSource[T](f: () => Future[T]) extends GraphStage[SourceShape[T]] {
  require(f != null, "f should not be null.")
  private val out = Outlet[T]("LazyFutureSource.out")
  val shape: SourceShape[T] = SourceShape(out)
  override def initialAttributes: Attributes =
    DefaultAttributes.lazyFutureSource and
    SourceLocation.forLambda(f)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      override def onPull(): Unit = {
        val future = f()
        ReactiveStreamsCompliance.requireNonNullElement(future)
        future.value match {
          case Some(result) => handle(result)
          case None =>
            val cb = getAsyncCallback[Try[T]](handle).invoke _
            future.onComplete(cb)(ExecutionContexts.parasitic)
        }
        setHandler(out, eagerTerminateOutput) // After first pull we won't produce anything more
      }

      private def handle(result: Try[T]): Unit = result match {
        case scala.util.Success(null) => completeStage()
        case scala.util.Success(v)    => emit(out, v, () => completeStage())
        case scala.util.Failure(t)    => failStage(t)
      }

      setHandler(out, this)
    }
}
