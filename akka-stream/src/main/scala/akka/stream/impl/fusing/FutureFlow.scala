/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.stream.{ AbruptStageTerminationException, Attributes, FlowShape, Inlet, NeverMaterializedException, Outlet }
import akka.stream.scaladsl.{ Flow, Keep, Source }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler }
import akka.util.OptionVal

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }

@InternalApi private[akka] final class FutureFlow[In, Out, M](futureFlow: Future[Flow[In, Out, M]])
    extends GraphStageWithMaterializedValue[FlowShape[In, Out], Future[M]] {
  val in = Inlet[In](s"${this}.in")
  val out = Outlet[Out](s"${this}.out")
  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
    val Attributes.NestedMaterializationCancellationPolicy(propagateToNestedMaterialization) =
      inheritedAttributes.mandatoryAttribute[Attributes.NestedMaterializationCancellationPolicy]
    val innerMatValue = Promise[M]
    val logic = new GraphStageLogic(shape) {

      //seems like we must set handlers BEFORE preStart
      setHandlers(in, out, Initializing)

      override def preStart(): Unit = {
        futureFlow.value match {
          case Some(tryFlow) =>
            Initializing.onFuture(tryFlow)
          case None =>
            val cb = getAsyncCallback(Initializing.onFuture)
            futureFlow.onComplete(cb.invoke)(ExecutionContexts.parasitic)
            //in case both ports are closed before future completion
            setKeepGoing(true)
        }
      }

      override def postStop(): Unit = {
        if (!innerMatValue.isCompleted) {
          innerMatValue.failure(new AbruptStageTerminationException(this))
        }
      }

      object Initializing extends InHandler with OutHandler {
        // we don't expect a push since we bever pull upstream during initialization
        override def onPush(): Unit = throw new IllegalStateException("unexpected push during initialization")

        var upstreamFailure = OptionVal.none[Throwable]
        override def onUpstreamFailure(ex: Throwable): Unit = {
          upstreamFailure = OptionVal.Some(ex)
        }

        //will later be propagated to the materialized flow (by examining isClosed(in))
        override def onUpstreamFinish(): Unit = {}

        //will later be propagated to the materialized flow (by examining isAvailable(out))
        override def onPull(): Unit = {}

        var downstreamCause = OptionVal.none[Throwable]
        override def onDownstreamFinish(cause: Throwable): Unit =
          if (propagateToNestedMaterialization) {
            downstreamCause = OptionVal.Some(cause)
          } else {
            innerMatValue.failure(new NeverMaterializedException(cause))
            cancelStage(cause)
          }

        def onFuture(futureRes: Try[Flow[In, Out, M]]) = futureRes match {
          case Failure(exception) =>
            setKeepGoing(false)
            innerMatValue.failure(new NeverMaterializedException(exception))
            failStage(exception)
          case Success(flow) =>
            //materialize flow, connect inlet and outlet, feed with potential events and set handlers
            connect(flow)
            setKeepGoing(false)
        }
      }
      def connect(flow: Flow[In, Out, M]): Unit = {
        val subSource = new SubSourceOutlet[In](s"${FutureFlow.this}.subIn")
        val subSink = new SubSinkInlet[Out](s"${FutureFlow.this}.subOut")

        subSource.setHandler {
          new OutHandler {
            override def onPull(): Unit = if (!isClosed(in)) tryPull(in)

            override def onDownstreamFinish(cause: Throwable): Unit = if (!isClosed(in)) cancel(in, cause)
          }
        }
        subSink.setHandler {
          new InHandler {
            override def onPush(): Unit = push(out, subSink.grab())

            override def onUpstreamFinish(): Unit = complete(out)

            override def onUpstreamFailure(ex: Throwable): Unit = fail(out, ex)
          }
        }
        Try {
          Source.fromGraph(subSource.source).viaMat(flow)(Keep.right).to(subSink.sink).run()(subFusingMaterializer)
        } match {
          case Success(matVal) =>
            innerMatValue.success(matVal)
            Initializing.upstreamFailure match {
              case OptionVal.Some(ex) =>
                subSource.fail(ex)
              case OptionVal.None =>
                if (isClosed(in))
                  subSource.complete()
            }
            Initializing.downstreamCause match {
              case OptionVal.Some(cause) =>
                subSink.cancel(cause)
              case OptionVal.None =>
                //todo: should this be invoked before and independently of checking downstreamCause?
                // in most case if downstream pulls and then closes, the pull is 'lost'. is it possible for some flows to actually care about this? (non-eager broadcast?)
                if (isAvailable(out)) {
                  subSink.pull()
                }
            }
          case Failure(ex) =>
            innerMatValue.failure(new NeverMaterializedException(ex))
            failStage(ex)
        }
        setHandlers(in, out, new InHandler with OutHandler {
          override def onPull(): Unit = subSink.pull()
          override def onDownstreamFinish(cause: Throwable): Unit = subSink.cancel(cause)

          override def onPush(): Unit = subSource.push(grab(in))

          override def onUpstreamFinish(): Unit = subSource.complete()

          override def onUpstreamFailure(ex: Throwable): Unit = subSource.fail(ex)
        })
      }
    }
    (logic, innerMatValue.future)
  }
}
