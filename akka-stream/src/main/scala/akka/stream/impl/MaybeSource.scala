/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.stream.{ AbruptStageTerminationException, Attributes, Outlet, SourceShape }
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, OutHandler }
import akka.util.OptionVal

import scala.concurrent.Promise
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi private[akka] object MaybeSource extends GraphStageWithMaterializedValue[SourceShape[AnyRef], Promise[Option[AnyRef]]] {
  val out = Outlet[AnyRef]("MaybeSource.out")
  override val shape = SourceShape(out)

  override protected def initialAttributes = DefaultAttributes.maybeSource

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Promise[Option[AnyRef]]) = {
    import scala.util.{ Success ⇒ ScalaSuccess, Failure ⇒ ScalaFailure }
    val promise = Promise[Option[AnyRef]]()
    val logic = new GraphStageLogic(shape) with OutHandler {

      private var arrivedEarly: OptionVal[AnyRef] = OptionVal.None

      override def preStart(): Unit = {
        promise.future.value match {
          case Some(value) ⇒
            // already completed, shortcut
            handleCompletion(value)
          case None ⇒
            // callback on future completion
            promise.future.onComplete(
              getAsyncCallback(handleCompletion).invoke
            )(ExecutionContexts.sameThreadExecutionContext)
        }
      }

      override def onPull(): Unit = arrivedEarly match {
        case OptionVal.Some(value) ⇒
          push(out, value)
          completeStage()
        case OptionVal.None ⇒
      }

      private def handleCompletion(elem: Try[Option[AnyRef]]): Unit = {
        elem match {
          case ScalaSuccess(None) ⇒
            completeStage()
          case ScalaSuccess(Some(value)) ⇒
            if (isAvailable(out)) {
              push(out, value)
              completeStage()
            } else {
              arrivedEarly = OptionVal.Some(value)
            }
          case ScalaFailure(ex) ⇒
            failStage(ex)
        }
      }

      override def onDownstreamFinish(): Unit = {
        promise.tryComplete(ScalaSuccess(None))
      }

      override def postStop(): Unit = {
        if (!promise.isCompleted)
          promise.tryFailure(new AbruptStageTerminationException(this))
      }

      setHandler(out, this)

    }
    (logic, promise)
  }

  override def toString = "MaybeSource"
}
