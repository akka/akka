/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream.impl.fusing.SubSink
import akka.stream.scaladsl.SinkQueueWithCancel
import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.scaladsl.{ Keep, Source }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler }

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object LazySource {
  def apply[T, M](sourceFactory: () ⇒ Source[T, M]) = new LazySource[T, M](sourceFactory)
}

final class LazySource[T, M](sourceFactory: () ⇒ Source[T, M]) extends GraphStageWithMaterializedValue[SourceShape[T], Future[M]] {
  val out = Outlet[T]("LazySource.out")
  override val shape = SourceShape(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
    val matPromise = Promise[M]()
    val logic = new GraphStageLogic(shape) with OutHandler {

      override def onDownstreamFinish(): Unit = {
        matPromise.failure(new RuntimeException("Downstream canceled without triggering lazy source materialization"))
        completeStage()
      }

      override def onPull(): Unit = {
        val source = sourceFactory()
        val subSink = new SubSinkInlet[T]("LazySource")
        subSink.pull()

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            subSink.pull()
          }

          override def onDownstreamFinish(): Unit = {
            subSink.cancel()
            completeStage()
          }
        })

        subSink.setHandler(new InHandler {
          override def onPush(): Unit = {
            push(out, subSink.grab())
          }
        })

        // TODO shouldn't this be tryComplete(Try(...)) to fail mat value on fail to materialize source?
        // currently cargo-culting the LazySink
        matPromise.trySuccess(source.toMat(subSink.sink)(Keep.left).run()(subFusingMaterializer))
      }

      setHandler(out, this)
    }

    (logic, matPromise.future)
  }
}
