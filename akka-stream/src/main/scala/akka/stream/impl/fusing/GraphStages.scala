/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.Cancellable
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration
import akka.stream.impl.StreamLayout._
import akka.stream.impl.ReactiveStreamsCompliance

import scala.util.Try

/**
 * INTERNAL API
 */
private[akka] final case class GraphStageModule(shape: Shape,
                                                attributes: Attributes,
                                                stage: GraphStageWithMaterializedValue[Shape, Any]) extends Module {
  def carbonCopy: Module = replaceShape(shape.deepCopy())

  def replaceShape(s: Shape): Module =
    CopiedModule(s, Attributes.none, this)

  def subModules: Set[Module] = Set.empty

  def withAttributes(attributes: Attributes): Module = new GraphStageModule(shape, attributes, stage)
}

/**
 * INTERNAL API
 */
object GraphStages {

  /**
   * INERNAL API
   */
  private[stream] abstract class SimpleLinearGraphStage[T] extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T](Logging.simpleName(this) + ".in")
    val out = Outlet[T](Logging.simpleName(this) + ".out")
    override val shape = FlowShape(in, out)
  }

  object Identity extends SimpleLinearGraphStage[Any] {
    override def initialAttributes = Attributes.name("identityOp")

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }

    override def toString = "Identity"
  }

  def identity[T] = Identity.asInstanceOf[SimpleLinearGraphStage[T]]

  private class Detacher[T] extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("in")
    val out = Outlet[T]("out")
    override def initialAttributes = Attributes.name("Detacher")
    override val shape = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      var initialized = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(out)) {
            push(out, grab(in))
            tryPull(in)
          }
        }
        override def onUpstreamFinish(): Unit = {
          if (!isAvailable(in)) completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (isAvailable(in)) {
            push(out, grab(in))
            if (isClosed(in)) completeStage()
            else pull(in)
          }
        }
      })

      override def preStart(): Unit = tryPull(in)
    }

    override def toString = "Detacher"
  }

  private val _detacher = new Detacher[Any]
  def detacher[T]: GraphStage[FlowShape[T, T]] = _detacher.asInstanceOf[GraphStage[FlowShape[T, T]]]

  final class Breaker(callback: Breaker.Operation ⇒ Unit) {
    import Breaker._
    def complete(): Unit = callback(Complete)
    def fail(ex: Throwable): Unit = callback(Fail(ex))
  }

  object Breaker extends GraphStageWithMaterializedValue[FlowShape[Any, Any], Future[Breaker]] {
    sealed trait Operation
    case object Complete extends Operation
    case class Fail(ex: Throwable) extends Operation

    override val initialAttributes = Attributes.name("breaker")
    override val shape = FlowShape(Inlet[Any]("breaker.in"), Outlet[Any]("breaker.out"))

    override def createLogicAndMaterializedValue(attr: Attributes) = {
      val promise = Promise[Breaker]

      val logic = new GraphStageLogic(shape) {

        passAlong(shape.in, shape.out)
        setHandler(shape.out, eagerTerminateOutput)

        override def preStart(): Unit = {
          pull(shape.in)
          promise.success(new Breaker(getAsyncCallback[Operation] {
            case Complete ⇒ completeStage()
            case Fail(ex) ⇒ failStage(ex)
          }.invoke))
        }
      }

      (logic, promise.future)
    }
  }

  def breaker[T]: Graph[FlowShape[T, T], Future[Breaker]] = Breaker.asInstanceOf[Graph[FlowShape[T, T], Future[Breaker]]]

  object BidiBreaker extends GraphStageWithMaterializedValue[BidiShape[Any, Any, Any, Any], Future[Breaker]] {
    import Breaker._

    override val initialAttributes = Attributes.name("breaker")
    override val shape = BidiShape(
      Inlet[Any]("breaker.in1"), Outlet[Any]("breaker.out1"),
      Inlet[Any]("breaker.in2"), Outlet[Any]("breaker.out2"))

    override def createLogicAndMaterializedValue(attr: Attributes) = {
      val promise = Promise[Breaker]

      val logic = new GraphStageLogic(shape) {

        setHandler(shape.in1, new InHandler {
          override def onPush(): Unit = push(shape.out1, grab(shape.in1))
          override def onUpstreamFinish(): Unit = complete(shape.out1)
          override def onUpstreamFailure(ex: Throwable): Unit = fail(shape.out1, ex)
        })
        setHandler(shape.in2, new InHandler {
          override def onPush(): Unit = push(shape.out2, grab(shape.in2))
          override def onUpstreamFinish(): Unit = complete(shape.out2)
          override def onUpstreamFailure(ex: Throwable): Unit = fail(shape.out2, ex)
        })
        setHandler(shape.out1, new OutHandler {
          override def onPull(): Unit = pull(shape.in1)
          override def onDownstreamFinish(): Unit = cancel(shape.in1)
        })
        setHandler(shape.out2, new OutHandler {
          override def onPull(): Unit = pull(shape.in2)
          override def onDownstreamFinish(): Unit = cancel(shape.in2)
        })

        override def preStart(): Unit = {
          promise.success(new Breaker(getAsyncCallback[Operation] {
            case Complete ⇒ completeStage()
            case Fail(ex) ⇒ failStage(ex)
          }.invoke))
        }
      }

      (logic, promise.future)
    }
  }

  def bidiBreaker[T1, T2]: Graph[BidiShape[T1, T1, T2, T2], Future[Breaker]] = BidiBreaker.asInstanceOf[Graph[BidiShape[T1, T1, T2, T2], Future[Breaker]]]

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
    override def initialAttributes = Attributes.name("TickSource")
    override val shape = SourceShape(out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Cancellable) = {
      import TickSource._

      val cancelled = new AtomicBoolean(false)
      val cancellable = new TickSourceCancellable(cancelled)

      val logic = new TimerGraphStageLogic(shape) {
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

        override def toString: String = "TickSourceLogic"
      }

      (logic, cancellable)
    }

    override def toString: String = "TickSource"
  }

  /**
   * INTERNAL API.
   *
   * This source is not reusable, it is only created internally.
   */
  private[stream] class MaterializedValueSource[T](val computation: MaterializedValueNode, val out: Outlet[T]) extends GraphStage[SourceShape[T]] {
    def this(computation: MaterializedValueNode) = this(computation, Outlet[T]("matValue"))
    override def initialAttributes: Attributes = Attributes.name("matValueSource")
    override val shape = SourceShape(out)

    private val promise = Promise[T]
    def setValue(t: T): Unit = promise.success(t)

    def copySrc: MaterializedValueSource[T] = new MaterializedValueSource(computation, out)

    override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
      setHandler(out, eagerTerminateOutput)
      override def preStart(): Unit = {
        val cb = getAsyncCallback[T](t ⇒ emit(out, t, () ⇒ completeStage()))
        promise.future.foreach(cb.invoke)(ExecutionContexts.sameThreadExecutionContext)
      }
    }

    override def toString: String = s"MatValSrc($computation)"
  }

  private[stream] class SingleSource[T](val elem: T) extends GraphStage[SourceShape[T]] {
    ReactiveStreamsCompliance.requireNonNullElement(elem)
    val out = Outlet[T]("single.out")
    val shape = SourceShape(out)
    override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          push(out, elem)
          completeStage()
        }
      })
    }
    override def toString: String = s"SingleSource($elem)"
  }

  private[stream] final class FutureSource[T](val future: Future[T]) extends GraphStage[SourceShape[T]] {
    ReactiveStreamsCompliance.requireNonNullElement(future)
    val shape = SourceShape(Outlet[T]("future.out"))
    val out = shape.out
    override def initialAttributes: Attributes = DefaultAttributes.futureSource
    override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          val cb = getAsyncCallback[Try[T]] {
            case scala.util.Success(v) ⇒ emit(out, v, () ⇒ completeStage())
            case scala.util.Failure(t) ⇒ failStage(t)
          }.invoke _
          future.onComplete(cb)(ExecutionContexts.sameThreadExecutionContext)
          setHandler(out, eagerTerminateOutput) // After first pull we won't produce anything more
        }
      })
    }
    override def toString: String = "FutureSource"
  }
}
