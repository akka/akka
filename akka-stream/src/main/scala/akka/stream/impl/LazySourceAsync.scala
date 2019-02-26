/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.stage._

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal


/**
 * INTERNAL API
 */
@InternalApi private[akka] final class LazySourceAsync[T, M](sourceFactory: () ⇒ Future[Source[T, M]]) extends GraphStageWithMaterializedValue[SourceShape[T], Future[Option[M]]] {
  val out = Outlet[T]("LazySourceAsync.out")
  override val shape = SourceShape(out)

  override protected def initialAttributes = DefaultAttributes.lazySource

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Option[M]]) = {
    val matPromise = Promise[Option[M]]()
    val logic = new GraphStageLogic(shape) with OutHandler {

      override def onDownstreamFinish(): Unit = {
        matPromise.success(None)
        super.onDownstreamFinish()
      }

      override def onPull(): Unit = {

        val cb: AsyncCallback[Try[Source[T, M]]] =
          getAsyncCallback {
            case Success(source) ⇒
              // check if the stage is still in need for the lazy source
              // (there could have been an onDownstreamFinish in the meantime that has completed the promise)
              if (!matPromise.isCompleted) {
                try {
                  val mat = switchTo(source)
                  matPromise.success(Some(mat))
                } catch {
                  case NonFatal(e) ⇒
                    matPromise.failure(e)
                    failStage(e)
                }
              }
            case Failure(e) ⇒
              matPromise.tryFailure(e)
              failStage(e)
          }

        try {
          sourceFactory().onComplete(cb.invoke)(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) ⇒
            matPromise.failure(e)
            failStage(e)
        }

      }

      setHandler(out, this)

      private def switchTo(source: Source[T, M]): M = {

        val subSink = new SubSinkInlet[T]("LazySourceAsync")
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
          subFusingMaterializer.materialize(source.toMat(subSink.sink)(Keep.left), inheritedAttributes)
        } catch {
          case NonFatal(ex) ⇒
            subSink.cancel()
            throw ex
        }
      }

    }

    (logic, matPromise.future)
  }

  override def toString = "LazySourceAsync"
}
