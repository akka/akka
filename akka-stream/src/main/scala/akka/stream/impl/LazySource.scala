/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.scaladsl.{ Keep, Source }
import akka.stream.stage._

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object LazySource {
  def apply[T, M](sourceFactory: () => Source[T, M]) = new LazySource[T, M](sourceFactory)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class LazySource[T, M](sourceFactory: () => Source[T, M])
    extends GraphStageWithMaterializedValue[SourceShape[T], Future[M]] {
  val out = Outlet[T]("LazySource.out")
  override val shape = SourceShape(out)

  override protected def initialAttributes = DefaultAttributes.lazySource

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

        try {
          val matVal = subFusingMaterializer.materialize(source.toMat(subSink.sink)(Keep.left), inheritedAttributes)
          matPromise.trySuccess(matVal)
        } catch {
          case NonFatal(ex) =>
            subSink.cancel()
            failStage(ex)
            matPromise.tryFailure(ex)
        }
      }

      setHandler(out, this)

      override def postStop() = {
        matPromise.tryFailure(new RuntimeException("LazySource stopped without completing the materialized future"))
      }
    }

    (logic, matPromise.future)
  }

  override def toString = "LazySource"
}
