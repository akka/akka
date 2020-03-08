/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.stream.{Attributes, FlowShape, Inlet, NeverMaterializedException, Outlet}
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import akka.util.OptionVal

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

@InternalApi private[akka] final class FutureFlow[In, Out, M](future : Future[Flow[In, Out, M]]) extends GraphStageWithMaterializedValue[FlowShape[In, Out], Future[M]] {
  val in = Inlet[In](s"${this}.in")
  val out = Outlet[Out](s"${this}.out")
  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
    val pr = Promise[M]
    val logic = new GraphStageLogic(shape) {

      //seems like we must set handlers BEFORE preStart
      setHandlers(in, out, initializing)

      override def preStart(): Unit = {
        super.preStart()
        future.value match {
          case Some(tr) =>
            initializing.onFuture(tr)
          case None =>
            val cb = getAsyncCallback(initializing.onFuture)
            future.onComplete(cb.invoke)(ExecutionContexts.sameThreadExecutionContext)
            //in case both ports are closed before future completion
            setKeepGoing(true)
        }
      }

      override def postStop(): Unit = {
        if(!pr.isCompleted) {
          pr.failure(new NeverMaterializedException())
        }
        super.postStop()
      }

      object initializing extends InHandler with OutHandler {
        override def onPush(): Unit = {
          //just leave it in the port
          println("initialized: pushed")
        }

        var upstreamFailure = OptionVal.none[Throwable]
        override def onUpstreamFailure(ex: Throwable): Unit = {
          upstreamFailure = OptionVal.Some(ex)
        }

        override def onPull(): Unit = pull(in)

        var downstreamCause = OptionVal.none[Throwable]
        override def onDownstreamFinish(cause: Throwable): Unit = {
          downstreamCause = OptionVal.Some(cause)
        }

        def onFuture(futureRes : Try[Flow[In, Out, M]]) = futureRes match {
          case Failure(exception) =>
            setKeepGoing(false)
            pr.failure(new NeverMaterializedException(exception))
            failStage(exception)
          case Success(flow) =>
            //materialize flow, connect to inlets, feed with potential events and set handlers
            val initialized = new Initialized(flow)
            setHandlers(in, out, initialized)
            setKeepGoing(false)
        }
      }
      class Initialized(fl : Flow[In, Out, M]) extends InHandler with OutHandler{
        val subSource = new SubSourceOutlet[In](s"${FutureFlow.this}.subIn")
        val subSink = new SubSinkInlet[Out](s"${FutureFlow.this}.subOut")

        subSource.setHandler {
          new OutHandler {
            override def onPull(): Unit = {
              if (!isClosed(in) && !hasBeenPulled(in)) {
                pull(in)
              }
            }

            override def onDownstreamFinish(cause: Throwable): Unit = {
              if (!isClosed(in)) {
                cancel(in, cause)
              }
            }
          }
        }
        subSink.setHandler {
          new InHandler {
            override def onPush(): Unit = {
              push(out, subSink.grab())
            }

            override def onUpstreamFinish(): Unit = {
              complete(out)
            }

            override def onUpstreamFailure(ex: Throwable): Unit = {
              fail(out, ex)
            }
          }
        }
        pr.complete{
          Try {
            Source
              .fromGraph(subSource.source)
              .viaMat(fl)(Keep.right)
              .to(subSink.sink)
              .run()(subFusingMaterializer)
          }
        }
        pr.future.value.get match {
          case Success(_) =>
            if(isAvailable(in)) {
              subSource.push(grab(in))
            }
            initializing.upstreamFailure match {
              case OptionVal.Some(ex) =>
                subSource.fail(ex)
              case OptionVal.None =>
                if(isClosed(in))
                  subSource.complete()
            }
            initializing.downstreamCause match {
              case OptionVal.Some(cause) =>
                subSink.cancel(cause)
              case OptionVal.None =>
                if(hasBeenPulled(in)) {
                  subSink.pull()
                }
            }
          case Failure(ex) =>
            failStage(ex)
        }

        override def onPull(): Unit = {
          subSink.pull()
        }

        override def onDownstreamFinish(cause: Throwable): Unit = {
          subSink.cancel(cause)
          //super.onDownstreamFinish(cause)
        }

        override def onPush(): Unit = {
          subSource.push(grab(in))
        }

        override def onUpstreamFinish(): Unit = {
          subSource.complete()
          //super.onUpstreamFinish()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          subSource.fail(ex)
          //super.onUpstreamFailure(ex)
        }
      }
    }
    (logic, pr.future)
  }
}
