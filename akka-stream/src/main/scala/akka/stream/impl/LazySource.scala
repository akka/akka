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
  def apply[T, M](createSource: ⇒ Source[T, M]): LazySource[T, M] = new LazySource[T, M](createSource)
}

final class LazySource[T, M](createSource: ⇒ Source[T, M]) extends GraphStageWithMaterializedValue[SourceShape[T], Future[M]] {
  val out = Outlet[T]("LazySource.out")
  override val shape = SourceShape(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
    val matPromise = Promise[M]()
    val logic = new GraphStageLogic(shape) with OutHandler {

      var sinkQueue: SinkQueueWithCancel[T] = _

      val cb = getAsyncCallback[Try[Option[T]]] {
        case Success(Some(t))            ⇒ emit(out, t)
        case Success(None)               ⇒ completeStage()
        case Failure(ex) if NonFatal(ex) ⇒ failStage(ex)
      }.invoke _

      override def onPull(): Unit = {
        if (sinkQueue eq null) {
          // first pull since starting, create and connect actual source
          try {
            val source = createSource
            val (mat, queue) = source.toMat(new QueueSink().addAttributes(Attributes.inputBuffer(1, 1)))(Keep.both).run()(materializer)
            matPromise.success(mat)
            sinkQueue = queue
            doPull()
          } catch {
            case ex if NonFatal(ex) ⇒
              matPromise.failure(ex)
              failStage(ex)
          }
        } else {
          doPull()
        }
      }

      private def doPull(): Unit = {
        sinkQueue.pull().onComplete(cb)(materializer.executionContext)
      }

      override def onDownstreamFinish(): Unit = {
        if (sinkQueue ne null) sinkQueue.cancel()
        super.onDownstreamFinish()
      }

      setHandler(out, this)
    }

    (logic, matPromise.future)
  }
}
