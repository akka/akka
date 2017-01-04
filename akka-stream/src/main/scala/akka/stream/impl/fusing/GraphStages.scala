/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.Done
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import akka.actor.Cancellable
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.stream.FlowMonitorState._
import akka.stream._
import akka.stream.scaladsl._
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
final case class GraphStageModule(
  shape:      Shape,
  attributes: Attributes,
  stage:      GraphStageWithMaterializedValue[Shape, Any]) extends AtomicModule {
  override def carbonCopy: Module = CopiedModule(shape.deepCopy(), Attributes.none, this)

  override def replaceShape(s: Shape): Module =
    if (s != shape) CompositeModule(this, s)
    else this

  override def withAttributes(attributes: Attributes): Module =
    if (attributes ne this.attributes) new GraphStageModule(shape, attributes, stage)
    else this

  override def toString: String = f"GraphStage($stage) [${System.identityHashCode(this)}%08x]"
}

/**
 * INTERNAL API
 */
object GraphStages {

  /**
   * INTERNAL API
   */
  abstract class SimpleLinearGraphStage[T] extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T](Logging.simpleName(this) + ".in")
    val out = Outlet[T](Logging.simpleName(this) + ".out")
    override val shape = FlowShape(in, out)
  }

  object Identity extends SimpleLinearGraphStage[Any] {
    override def initialAttributes = DefaultAttributes.identityOp

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
      def onPush(): Unit = push(out, grab(in))
      def onPull(): Unit = pull(in)

      setHandler(in, this)
      setHandler(out, this)
    }

    override def toString = "Identity"
  }

  def identity[T] = Identity.asInstanceOf[SimpleLinearGraphStage[T]]

  /**
   * INTERNAL API
   */
  final class Detacher[T] extends SimpleLinearGraphStage[T] {
    override def initialAttributes = DefaultAttributes.detacher

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

      def onPush(): Unit = {
        if (isAvailable(out)) {
          push(out, grab(in))
          tryPull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (!isAvailable(in)) completeStage()
      }

      def onPull(): Unit = {
        if (isAvailable(in)) {
          push(out, grab(in))
          if (isClosed(in)) completeStage()
          else pull(in)
        }
      }

      setHandlers(in, out, this)

      override def preStart(): Unit = tryPull(in)
    }

    override def toString = "Detacher"
  }

  private val _detacher = new Detacher[Any]
  def detacher[T]: GraphStage[FlowShape[T, T]] = _detacher.asInstanceOf[GraphStage[FlowShape[T, T]]]

  private object TerminationWatcher extends GraphStageWithMaterializedValue[FlowShape[Any, Any], Future[Done]] {
    val in = Inlet[Any]("terminationWatcher.in")
    val out = Outlet[Any]("terminationWatcher.out")
    override val shape = FlowShape(in, out)
    override def initialAttributes: Attributes = DefaultAttributes.terminationWatcher

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
      val finishPromise = Promise[Done]()

      (new GraphStageLogic(shape) with InHandler with OutHandler {
        def onPush(): Unit = push(out, grab(in))

        override def onUpstreamFinish(): Unit = {
          finishPromise.success(Done)
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          finishPromise.failure(ex)
          failStage(ex)
        }

        def onPull(): Unit = pull(in)

        override def onDownstreamFinish(): Unit = {
          finishPromise.success(Done)
          completeStage()
        }

        setHandlers(in, out, this)
      }, finishPromise.future)
    }

    override def toString = "TerminationWatcher"
  }

  def terminationWatcher[T]: GraphStageWithMaterializedValue[FlowShape[T, T], Future[Done]] =
    TerminationWatcher.asInstanceOf[GraphStageWithMaterializedValue[FlowShape[T, T], Future[Done]]]

  private class FlowMonitorImpl[T] extends AtomicReference[Any](Initialized) with FlowMonitor[T] {
    override def state = get match {
      case s: StreamState[_] ⇒ s.asInstanceOf[StreamState[T]]
      case msg               ⇒ Received(msg.asInstanceOf[T])
    }
  }

  private class MonitorFlow[T] extends GraphStageWithMaterializedValue[FlowShape[T, T], FlowMonitor[T]] {
    val in = Inlet[T]("FlowMonitor.in")
    val out = Outlet[T]("FlowMonitor.out")
    val shape = FlowShape.of(in, out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, FlowMonitor[T]) = {
      val monitor: FlowMonitorImpl[T] = new FlowMonitorImpl[T]

      val logic: GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

        def onPush(): Unit = {
          val msg = grab(in)
          push(out, msg)
          monitor.set(if (msg.isInstanceOf[StreamState[_]]) Received(msg) else msg)
        }

        override def onUpstreamFinish(): Unit = {
          super.onUpstreamFinish()
          monitor.set(Finished)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          super.onUpstreamFailure(ex)
          monitor.set(Failed(ex))
        }

        def onPull(): Unit = pull(in)

        override def onDownstreamFinish(): Unit = {
          super.onDownstreamFinish()
          monitor.set(Finished)
        }

        setHandler(in, this)
        setHandler(out, this)

        override def toString = "MonitorFlowLogic"
      }

      (logic, monitor)
    }

    override def toString = "MonitorFlow"
  }

  def monitor[T]: GraphStageWithMaterializedValue[FlowShape[T, T], FlowMonitor[T]] =
    new MonitorFlow[T]

  private object TickSource {
    class TickSourceCancellable(cancelled: AtomicBoolean) extends Cancellable {
      private val cancelPromise = Promise[Done]()

      def cancelFuture: Future[Done] = cancelPromise.future

      override def cancel(): Boolean = {
        if (!isCancelled) cancelPromise.trySuccess(Done)
        true
      }

      override def isCancelled: Boolean = cancelled.get()
    }
  }

  final class TickSource[T](val initialDelay: FiniteDuration, val interval: FiniteDuration, val tick: T)
    extends GraphStageWithMaterializedValue[SourceShape[T], Cancellable] {
    override val shape = SourceShape(Outlet[T]("TickSource.out"))
    val out = shape.out
    override def initialAttributes: Attributes = DefaultAttributes.tickSource
    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Cancellable) = {

      val logic = new TimerGraphStageLogic(shape) with Cancellable {
        val cancelled = new AtomicBoolean(false)
        val cancelCallback: AtomicReference[Option[AsyncCallback[Unit]]] = new AtomicReference(None)

        override def preStart() = {
          cancelCallback.set(Some(getAsyncCallback[Unit](_ ⇒ completeStage())))
          if (cancelled.get)
            completeStage()
          else
            schedulePeriodicallyWithInitialDelay("TickTimer", initialDelay, interval)
        }

        setHandler(out, eagerTerminateOutput)

        override protected def onTimer(timerKey: Any) =
          if (isAvailable(out) && !isCancelled) push(out, tick)

        override def cancel() = {
          val success = !cancelled.getAndSet(true)
          if (success) cancelCallback.get.foreach(_.invoke(()))
          success
        }

        override def isCancelled = cancelled.get

        override def toString: String = "TickSourceLogic"
      }

      (logic, logic)
    }

    override def toString: String = s"TickSource($initialDelay, $interval, $tick)"
  }

  /**
   * INTERNAL API.
   *
   * This source is not reusable, it is only created internally.
   */
  final class MaterializedValueSource[T](val computation: MaterializedValueNode, val out: Outlet[T]) extends GraphStage[SourceShape[T]] {
    def this(computation: MaterializedValueNode) = this(computation, Outlet[T]("matValue"))
    override def initialAttributes: Attributes = DefaultAttributes.materializedValueSource
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

    override def toString: String = s"MaterializedValueSource($computation)"
  }

  final class SingleSource[T](val elem: T) extends GraphStage[SourceShape[T]] {
    override def initialAttributes: Attributes = DefaultAttributes.singleSource
    ReactiveStreamsCompliance.requireNonNullElement(elem)
    val out = Outlet[T]("single.out")
    val shape = SourceShape(out)
    def createLogic(attr: Attributes) =
      new GraphStageLogic(shape) with OutHandler {
        def onPull(): Unit = {
          push(out, elem)
          completeStage()
        }
        setHandler(out, this)
      }

    override def toString: String = s"SingleSource($elem)"
  }

  final class FutureSource[T](val future: Future[T]) extends GraphStage[SourceShape[T]] {
    ReactiveStreamsCompliance.requireNonNullElement(future)
    val shape = SourceShape(Outlet[T]("future.out"))
    val out = shape.out
    override def initialAttributes: Attributes = DefaultAttributes.futureSource
    override def createLogic(attr: Attributes) =
      new GraphStageLogic(shape) with OutHandler {
        def onPull(): Unit = {
          val cb = getAsyncCallback[Try[T]] {
            case scala.util.Success(v) ⇒ emit(out, v, () ⇒ completeStage())
            case scala.util.Failure(t) ⇒ failStage(t)
          }.invoke _
          future.onComplete(cb)(ExecutionContexts.sameThreadExecutionContext)
          setHandler(out, eagerTerminateOutput) // After first pull we won't produce anything more
        }

        setHandler(out, this)
      }

    override def toString: String = "FutureSource"
  }

  /**
   * INTERNAL API
   * Discards all received elements.
   */
  object IgnoreSink extends GraphStageWithMaterializedValue[SinkShape[Any], Future[Done]] {

    val in = Inlet[Any]("Ignore.in")
    val shape = SinkShape(in)

    override def initialAttributes = DefaultAttributes.ignoreSink

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
      val promise = Promise[Done]()
      val logic = new GraphStageLogic(shape) with InHandler {

        override def preStart(): Unit = pull(in)
        override def onPush(): Unit = pull(in)

        override def onUpstreamFinish(): Unit = {
          super.onUpstreamFinish()
          promise.trySuccess(Done)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          super.onUpstreamFailure(ex)
          promise.tryFailure(ex)
        }

        setHandler(in, this)
      }

      (logic, promise.future)
    }

  }

  /**
   * INTERNAL API.
   *
   * Fusing graphs that have cycles involving FanIn stages might lead to deadlocks if
   * demand is not carefully managed.
   *
   * This means that FanIn stages need to early pull every relevant input on startup.
   * This can either be implemented inside the stage itself, or this method can be used,
   * which adds a detacher stage to every input.
   */
  private[stream] def withDetachedInputs[T](stage: GraphStage[UniformFanInShape[T, T]]) =
    GraphDSL.create() { implicit builder ⇒
      import GraphDSL.Implicits._
      val concat = builder.add(stage)
      val ds = concat.inSeq.map { inlet ⇒
        val detacher = builder.add(GraphStages.detacher[T])
        detacher ~> inlet
        detacher.in
      }
      UniformFanInShape(concat.out, ds: _*)
    }

}
