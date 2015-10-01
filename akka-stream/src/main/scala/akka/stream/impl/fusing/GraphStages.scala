/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.Cancellable
import akka.stream._
import akka.stream.stage._

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
object GraphStages {

  /**
   * INERNAL API
   */
  private[stream] abstract class SimpleLinearGraphStage[T] extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("in")
    val out = Outlet[T]("out")
    override val shape = FlowShape(in, out)

    protected abstract class SimpleLinearStageLogic extends GraphStageLogic {
      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
        override def onDownstreamFinish(): Unit = completeStage()
      })
    }

  }

  class Identity[T] extends SimpleLinearGraphStage[T] {

    override def createLogic: GraphStageLogic = new SimpleLinearStageLogic {
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onUpstreamFinish(): Unit = completeStage()
        override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
      })
    }

    override def toString = "Identity"
  }

  class Detacher[T] extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("in")
    val out = Outlet[T]("out")
    override val shape = FlowShape(in, out)

    override def createLogic: GraphStageLogic = new GraphStageLogic {
      var initialized = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(out)) {
            push(out, grab(in))
            pull(in)
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (!initialized) {
            pull(in)
            initialized = true
          } else if (isAvailable(in)) {
            push(out, grab(in))
            if (!hasBeenPulled(in)) pull(in)
          }
        }
      })

    }

    override def toString = "Detacher"
  }

  private object TickSource {
    class TickSourceCancellable(cancelled: AtomicBoolean) extends Cancellable {
      private val cancelPromise = Promise[Unit]()

      def cancelFuture: Future[Unit] = cancelPromise.future

      override def cancel(): Boolean = {
        if (!isCancelled) cancelPromise.trySuccess(())
        true
      }

      override def isCancelled: Boolean = cancelled.get()
    }
  }

  class TickSource[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: T)
    extends GraphStageWithMaterializedValue[SourceShape[T], Cancellable] {

    val out = Outlet[T]("TimerSource.out")
    override val shape = SourceShape(out)

    override def createLogicAndMaterializedValue: (GraphStageLogic, Cancellable) = {
      import TickSource._

      val cancelled = new AtomicBoolean(false)
      val cancellable = new TickSourceCancellable(cancelled)

      val logic = new GraphStageLogic {
        override def preStart() = {
          schedulePeriodicallyWithInitialDelay("TickTimer", initialDelay, interval)
          val callback = getAsyncCallback[Unit]((_) ⇒ {
            completeStage()
            cancelled.set(true)
          })

          cancellable.cancelFuture.onComplete(_ ⇒ callback.invoke(()))(interpreter.materializer.executionContext)
        }

        setHandler(out, new OutHandler {
          override def onPull() = () // Do nothing
        })

        override protected def onTimer(timerKey: Any) =
          if (isAvailable(out)) push(out, tick)
      }

      (logic, cancellable)
    }
  }
}
