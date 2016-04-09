/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.event.Logging.LogLevel
import akka.event.{ LogSource, Logging, LoggingAdapter }
import akka.stream.Attributes.{ InputBuffer, LogLevels }
import akka.stream.OverflowStrategies._
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.impl.{ Buffer ⇒ BufferImpl, ReactiveStreamsCompliance }
import akka.stream.scaladsl.{ SourceQueue, Source }
import akka.stream.stage._
import akka.stream.{ Supervision, _ }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import akka.stream.ActorAttributes.SupervisionStrategy
import scala.concurrent.duration.{ FiniteDuration, _ }
import akka.stream.impl.Stages.DefaultAttributes

/**
 * INTERNAL API
 */
private[akka] final case class Map[In, Out](f: In ⇒ Out, decider: Supervision.Decider) extends PushStage[In, Out] {
  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = ctx.push(f(elem))

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

/**
 * INTERNAL API
 */
private[akka] final case class Filter[T](p: T ⇒ Boolean, decider: Supervision.Decider) extends PushStage[T, T] {
  override def onPush(elem: T, ctx: Context[T]): SyncDirective =
    if (p(elem)) ctx.push(elem)
    else ctx.pull()

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

/**
 * INTERNAL API
 */
private[akka] final case class TakeWhile[T](p: T ⇒ Boolean) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.takeWhile

  override def toString: String = "TakeWhile"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler with InHandler {
      override def toString = "TakeWhileLogic"

      def decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)

      override def onPush(): Unit = {
        try {
          val elem = grab(in)
          if (p(elem)) {
            push(out, elem)
          } else {
            completeStage()
          }
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case _                ⇒ pull(in)
          }
        }
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
private[stream] final case class DropWhile[T](p: T ⇒ Boolean) extends GraphStage[FlowShape[T, T]] {
  val in = Inlet[T]("DropWhile.in")
  val out = Outlet[T]("DropWhile.out")
  override val shape = FlowShape(in, out)
  override def initialAttributes: Attributes = DefaultAttributes.dropWhile

  def createLogic(inheritedAttributes: Attributes) = new SupervisedGraphStageLogic(inheritedAttributes, shape) with InHandler with OutHandler {
    override def onPush(): Unit = {
      val elem = grab(in)
      withSupervision(() ⇒ p(elem)) match {
        case Some(flag) if flag ⇒ pull(in)
        case Some(flag) if !flag ⇒
          push(out, elem)
          setHandler(in, rest)
        case None ⇒ // do nothing
      }
    }

    def rest = new InHandler {
      def onPush() = push(out, grab(in))
    }

    override def onResume(t: Throwable): Unit = if (!hasBeenPulled(in)) pull(in)
    override def onPull(): Unit = pull(in)
    setHandlers(in, out, this)
  }
  override def toString = "DropWhile"
}

/**
 * INTERNAL API
 */
abstract private[stream] class SupervisedGraphStageLogic(inheritedAttributes: Attributes, shape: Shape) extends GraphStageLogic(shape) {
  private lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
  def withSupervision[T](f: () ⇒ T): Option[T] =
    try { Some(f()) } catch {
      case NonFatal(ex) ⇒
        decider(ex) match {
          case Supervision.Stop    ⇒ onStop(ex)
          case Supervision.Resume  ⇒ onResume(ex)
          case Supervision.Restart ⇒ onRestart(ex)
        }
        None
    }

  def onResume(t: Throwable): Unit
  def onStop(t: Throwable): Unit = failStage(t)
  def onRestart(t: Throwable): Unit = onResume(t)
}

private[stream] object Collect {
  // Cached function that can be used with PartialFunction.applyOrElse to ensure that A) the guard is only applied once,
  // and the caller can check the returned value with Collect.notApplied to query whether the PF was applied or not.
  // Prior art: https://github.com/scala/scala/blob/v2.11.4/src/library/scala/collection/immutable/List.scala#L458
  final val NotApplied: Any ⇒ Any = _ ⇒ Collect.NotApplied
}

/**
 * INTERNAL API
 */
private[stream] final case class Collect[In, Out](pf: PartialFunction[In, Out]) extends GraphStage[FlowShape[In, Out]] {
  val in = Inlet[In]("Collect.in")
  val out = Outlet[Out]("Collect.out")
  override val shape = FlowShape(in, out)
  override def initialAttributes: Attributes = DefaultAttributes.collect

  def createLogic(inheritedAttributes: Attributes) = new SupervisedGraphStageLogic(inheritedAttributes, shape) with InHandler with OutHandler {
    import Collect.NotApplied
    val wrappedPf = () ⇒ pf.applyOrElse(grab(in), NotApplied)

    override def onPush(): Unit = withSupervision(wrappedPf) match {
      case Some(result) ⇒ result match {
        case NotApplied             ⇒ pull(in)
        case result: Out @unchecked ⇒ push(out, result)
      }
      case None ⇒ //do nothing
    }

    override def onResume(t: Throwable): Unit = if (!hasBeenPulled(in)) pull(in)
    override def onPull(): Unit = pull(in)
    setHandlers(in, out, this)
  }
  override def toString = "Collect"
}

/**
 * INTERNAL API
 */
private[akka] final case class Recover[T](pf: PartialFunction[Throwable, T]) extends PushPullStage[T, T] {
  import Collect.NotApplied
  var recovered: Option[T] = None

  override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
    ctx.push(elem)
  }

  override def onPull(ctx: Context[T]): SyncDirective =
    recovered match {
      case Some(value) ⇒ ctx.pushAndFinish(value)
      case None        ⇒ ctx.pull()
    }

  override def onUpstreamFailure(t: Throwable, ctx: Context[T]): TerminationDirective = {
    pf.applyOrElse(t, NotApplied) match {
      case NotApplied ⇒ ctx.fail(t)
      case result: T @unchecked ⇒
        recovered = Some(result)
        ctx.absorbTermination()
    }
  }

}

/**
 * INTERNAL API
 */
private[akka] final case class Take[T](count: Long) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.take

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var left: Long = count

    override def onPush(): Unit = {
      val leftBefore = left
      if (leftBefore >= 1) {
        left = leftBefore - 1
        push(out, grab(in))
      }
      if (leftBefore <= 1) completeStage()
    }

    override def onPull(): Unit = {
      if (left > 0) pull(in)
      else completeStage()
    }

    setHandlers(in, out, this)
  }

  override def toString: String = "Take"
}

/**
 * INTERNAL API
 */
private[akka] final case class Drop[T](count: Long) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.drop

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var left: Long = count

    override def onPush(): Unit = {
      if (left > 0) {
        left -= 1
        pull(in)
      } else push(out, grab(in))
    }

    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

  override def toString: String = "Drop"
}

/**
 * INTERNAL API
 */
private[akka] final case class Scan[In, Out](zero: Out, f: (Out, In) ⇒ Out, decider: Supervision.Decider) extends PushPullStage[In, Out] {
  private var aggregator = zero
  private var pushedZero = false

  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = {
    if (pushedZero) {
      aggregator = f(aggregator, elem)
      ctx.push(aggregator)
    } else {
      aggregator = f(zero, elem)
      ctx.push(zero)
    }
  }

  override def onPull(ctx: Context[Out]): SyncDirective =
    if (!pushedZero) {
      pushedZero = true
      if (ctx.isFinishing) ctx.pushAndFinish(aggregator) else ctx.push(aggregator)
    } else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective =
    if (pushedZero) ctx.finish()
    else ctx.absorbTermination()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): Scan[In, Out] = copy()
}

/**
 * INTERNAL API
 */
private[akka] final case class Fold[In, Out](zero: Out, f: (Out, In) ⇒ Out, decider: Supervision.Decider) extends PushPullStage[In, Out] {
  private[this] var aggregator: Out = zero

  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = {
    aggregator = f(aggregator, elem)
    ctx.pull()
  }

  override def onPull(ctx: Context[Out]): SyncDirective =
    if (ctx.isFinishing) ctx.pushAndFinish(aggregator)
    else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective = ctx.absorbTermination()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): Fold[In, Out] = copy()
}

/**
 * INTERNAL API
 */
final case class Intersperse[T](start: Option[T], inject: T, end: Option[T]) extends GraphStage[FlowShape[T, T]] {
  ReactiveStreamsCompliance.requireNonNullElement(inject)
  if (start.isDefined) ReactiveStreamsCompliance.requireNonNullElement(start.get)
  if (end.isDefined) ReactiveStreamsCompliance.requireNonNullElement(end.get)

  private val in = Inlet[T]("in")
  private val out = Outlet[T]("out")

  override val shape = FlowShape(in, out)

  override def createLogic(attr: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val startInHandler = new InHandler {
      override def onPush(): Unit = {
        // if else (to avoid using Iterator[T].flatten in hot code)
        if (start.isDefined) emitMultiple(out, Iterator(start.get, grab(in)))
        else emit(out, grab(in))
        setHandler(in, restInHandler) // switch handler
      }

      override def onUpstreamFinish(): Unit = {
        emitMultiple(out, Iterator(start, end).flatten)
        completeStage()
      }
    }

    val restInHandler = new InHandler {
      override def onPush(): Unit = emitMultiple(out, Iterator(inject, grab(in)))

      override def onUpstreamFinish(): Unit = {
        if (end.isDefined) emit(out, end.get)
        completeStage()
      }
    }

    val outHandler = new OutHandler {
      override def onPull(): Unit = pull(in)
    }

    setHandler(in, startInHandler)
    setHandler(out, outHandler)
  }
}

/**
 * INTERNAL API
 */
private[akka] final case class Grouped[T](n: Int) extends PushPullStage[T, immutable.Seq[T]] {
  private val buf = {
    val b = Vector.newBuilder[T]
    b.sizeHint(n)
    b
  }
  private var left = n

  override def onPush(elem: T, ctx: Context[immutable.Seq[T]]): SyncDirective = {
    buf += elem
    left -= 1
    if (left == 0) {
      val emit = buf.result()
      buf.clear()
      left = n
      ctx.push(emit)
    } else ctx.pull()
  }

  override def onPull(ctx: Context[immutable.Seq[T]]): SyncDirective =
    if (ctx.isFinishing) {
      val elem = buf.result()
      buf.clear()
      left = n
      ctx.pushAndFinish(elem)
    } else ctx.pull()

  override def onUpstreamFinish(ctx: Context[immutable.Seq[T]]): TerminationDirective =
    if (left == n) ctx.finish()
    else ctx.absorbTermination()
}

/**
 * INTERNAL API
 */
private[stream] final case class LimitWeighted[T](n: Long, costFn: T ⇒ Long) extends GraphStage[FlowShape[T, T]] {
  val in = Inlet[T]("LimitWeighted.in")
  val out = Outlet[T]("LimitWeighted.out")
  override val shape = FlowShape(in, out)
  override def initialAttributes: Attributes = DefaultAttributes.limitWeighted

  def createLogic(inheritedAttributes: Attributes) = new SupervisedGraphStageLogic(inheritedAttributes, shape) with InHandler with OutHandler {
    private var left = n

    override def onPush(): Unit = {
      val elem = grab(in)
      withSupervision(() ⇒ costFn(elem)) match {
        case Some(wight) ⇒
          left -= wight
          if (left >= 0) push(out, elem) else failStage(new StreamLimitReachedException(n))
        case None ⇒ //do nothing
      }
    }
    override def onResume(t: Throwable): Unit = if (!hasBeenPulled(in)) pull(in)
    override def onRestart(t: Throwable): Unit = {
      left = n
      if (!hasBeenPulled(in)) pull(in)
    }
    override def onPull(): Unit = pull(in)
    setHandlers(in, out, this)
  }
  override def toString = "LimitWeighted"
}

/**
 * INTERNAL API
 */
private[akka] final case class Sliding[T](n: Int, step: Int) extends PushPullStage[T, immutable.Seq[T]] {
  private var buf = Vector.empty[T]

  override def onPush(elem: T, ctx: Context[immutable.Seq[T]]): SyncDirective = {
    buf :+= elem
    if (buf.size < n) {
      ctx.pull()
    } else if (buf.size == n) {
      ctx.push(buf)
    } else if (step > n) {
      if (buf.size == step)
        buf = Vector.empty
      ctx.pull()
    } else {
      buf = buf.drop(step)
      if (buf.size == n) ctx.push(buf)
      else ctx.pull()
    }
  }

  override def onPull(ctx: Context[immutable.Seq[T]]): SyncDirective =
    if (!ctx.isFinishing) ctx.pull()
    else if (buf.size >= n) ctx.finish()
    else ctx.pushAndFinish(buf)

  override def onUpstreamFinish(ctx: Context[immutable.Seq[T]]): TerminationDirective =
    if (buf.isEmpty) ctx.finish()
    else ctx.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] final case class Buffer[T](size: Int, overflowStrategy: OverflowStrategy) extends DetachedStage[T, T] {

  private var buffer: BufferImpl[T] = _

  override def preStart(ctx: LifecycleContext): Unit = {
    buffer = BufferImpl(size, ctx.materializer)
  }

  override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective =
    if (ctx.isHoldingDownstream) ctx.pushAndPull(elem)
    else enqueueAction(ctx, elem)

  override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
    if (ctx.isFinishing) {
      val elem = buffer.dequeue()
      if (buffer.isEmpty) ctx.pushAndFinish(elem)
      else ctx.push(elem)
    } else if (ctx.isHoldingUpstream) ctx.pushAndPull(buffer.dequeue())
    else if (buffer.isEmpty) ctx.holdDownstream()
    else ctx.push(buffer.dequeue())
  }

  override def onUpstreamFinish(ctx: DetachedContext[T]): TerminationDirective =
    if (buffer.isEmpty) ctx.finish()
    else ctx.absorbTermination()

  val enqueueAction: (DetachedContext[T], T) ⇒ UpstreamDirective =
    overflowStrategy match {
      case DropHead ⇒ (ctx, elem) ⇒
        if (buffer.isFull) buffer.dropHead()
        buffer.enqueue(elem)
        ctx.pull()
        case DropTail ⇒ (ctx, elem) ⇒
        if (buffer.isFull) buffer.dropTail()
        buffer.enqueue(elem)
        ctx.pull()
        case DropBuffer ⇒ (ctx, elem) ⇒
        if (buffer.isFull) buffer.clear()
        buffer.enqueue(elem)
        ctx.pull()
        case DropNew ⇒ (ctx, elem) ⇒
        if (!buffer.isFull) buffer.enqueue(elem)
        ctx.pull()
        case Backpressure ⇒ (ctx, elem) ⇒
        buffer.enqueue(elem)
        if (buffer.isFull) ctx.holdUpstream()
        else ctx.pull()
        case Fail ⇒ (ctx, elem) ⇒
        if (buffer.isFull) ctx.fail(new BufferOverflowException(s"Buffer overflow (max capacity was: $size)!"))
        else {
          buffer.enqueue(elem)
          ctx.pull()
        }
    }

}

/**
 * INTERNAL API
 */
private[akka] final case class Completed[T]() extends PushPullStage[T, T] {
  override def onPush(elem: T, ctx: Context[T]): SyncDirective = ctx.finish()

  override def onPull(ctx: Context[T]): SyncDirective = ctx.finish()
}

/**
 * INTERNAL API
 */
private[akka] final case class Batch[In, Out](max: Long, costFn: In ⇒ Long, seed: In ⇒ Out, aggregate: (Out, In) ⇒ Out)
  extends GraphStage[FlowShape[In, Out]] {

  val in = Inlet[In]("Batch.in")
  val out = Outlet[Out]("Batch.out")

  override val shape: FlowShape[In, Out] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)

    private var agg: Out = null.asInstanceOf[Out]
    private var left: Long = max
    private var pending: In = null.asInstanceOf[In]

    private def flush(): Unit = {
      if (agg != null) {
        push(out, agg)
        left = max
      }
      if (pending != null) {
        try {
          agg = seed(pending)
          left -= costFn(pending)
          pending = null.asInstanceOf[In]
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop    ⇒ failStage(ex)
            case Supervision.Restart ⇒ restartState()
            case Supervision.Resume ⇒
              pending = null.asInstanceOf[In]
          }
        }
      } else {
        agg = null.asInstanceOf[Out]
      }
    }

    override def preStart() = pull(in)

    setHandler(in, new InHandler {

      override def onPush(): Unit = {
        val elem = grab(in)
        val cost = costFn(elem)

        if (agg == null) {
          try {
            agg = seed(elem)
            left -= cost
          } catch {
            case NonFatal(ex) ⇒ decider(ex) match {
              case Supervision.Stop ⇒ failStage(ex)
              case Supervision.Restart ⇒
                restartState()
              case Supervision.Resume ⇒
            }
          }
        } else if (left < cost) {
          pending = elem
        } else {
          try {
            agg = aggregate(agg, elem)
            left -= cost
          } catch {
            case NonFatal(ex) ⇒ decider(ex) match {
              case Supervision.Stop ⇒ failStage(ex)
              case Supervision.Restart ⇒
                restartState()
              case Supervision.Resume ⇒
            }
          }
        }

        if (isAvailable(out)) flush()
        if (pending == null) pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        if (agg == null) completeStage()
      }
    })

    setHandler(out, new OutHandler {

      override def onPull(): Unit = {
        if (agg == null) {
          if (isClosed(in)) completeStage()
          else if (!hasBeenPulled(in)) pull(in)
        } else if (isClosed(in)) {
          push(out, agg)
          if (pending == null) completeStage()
          else {
            try {
              agg = seed(pending)
            } catch {
              case NonFatal(ex) ⇒ decider(ex) match {
                case Supervision.Stop   ⇒ failStage(ex)
                case Supervision.Resume ⇒
                case Supervision.Restart ⇒
                  restartState()
                  if (!hasBeenPulled(in)) pull(in)
              }
            }
            pending = null.asInstanceOf[In]
          }
        } else {
          flush()
          if (!hasBeenPulled(in)) pull(in)
        }

      }
    })

    private def restartState(): Unit = {
      agg = null.asInstanceOf[Out]
      left = max
      pending = null.asInstanceOf[In]
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] final class Expand[In, Out](extrapolate: In ⇒ Iterator[Out]) extends GraphStage[FlowShape[In, Out]] {
  private val in = Inlet[In]("expand.in")
  private val out = Outlet[Out]("expand.out")

  override def initialAttributes = DefaultAttributes.expand
  override val shape = FlowShape(in, out)

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
    private var iterator: Iterator[Out] = Iterator.empty
    private var expanded = false

    override def preStart(): Unit = pull(in)

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        iterator = extrapolate(grab(in))
        if (iterator.hasNext) {
          if (isAvailable(out)) {
            expanded = true
            pull(in)
            push(out, iterator.next())
          } else expanded = false
        } else pull(in)
      }
      override def onUpstreamFinish(): Unit = {
        if (iterator.hasNext && !expanded) () // need to wait
        else completeStage()
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (iterator.hasNext) {
          if (expanded == false) {
            expanded = true
            if (isClosed(in)) {
              push(out, iterator.next())
              completeStage()
            } else {
              // expand needs to pull first to be “fair” when upstream is not actually slow
              pull(in)
              push(out, iterator.next())
            }
          } else push(out, iterator.next())
        }
      }
    })
  }
}

/**
 * INTERNAL API
 */
private[akka] object MapAsync {
  final class Holder[T](var elem: Try[T], val cb: AsyncCallback[Holder[T]]) extends (Try[T] ⇒ Unit) {
    override def apply(t: Try[T]): Unit = {
      elem = t match {
        case Success(null) ⇒ Failure[T](ReactiveStreamsCompliance.elementMustNotBeNullException)
        case other         ⇒ other
      }
      cb.invoke(this)
    }
  }
  val NotYetThere = Failure(new Exception)
}

/**
 * INTERNAL API
 */
private[akka] final case class MapAsync[In, Out](parallelism: Int, f: In ⇒ Future[Out])
  extends GraphStage[FlowShape[In, Out]] {

  import MapAsync._

  private val in = Inlet[In]("MapAsync.in")
  private val out = Outlet[Out]("MapAsync.out")

  override def initialAttributes = DefaultAttributes.mapAsync
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      override def toString = s"MapAsync.Logic(buffer=$buffer)"

      //FIXME Put Supervision.stoppingDecider as a SupervisionStrategy on DefaultAttributes.mapAsync?
      lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
      var buffer: BufferImpl[Holder[Out]] = _
      val futureCB =
        getAsyncCallback[Holder[Out]](
          _.elem match {
            case Failure(e) if decider(e) == Supervision.Stop ⇒ failStage(e)
            case _ ⇒ if (isAvailable(out)) pushOne()
          })

      private[this] def todo = buffer.used

      override def preStart(): Unit = buffer = BufferImpl(parallelism, materializer)

      @tailrec private def pushOne(): Unit =
        if (buffer.isEmpty) {
          if (isClosed(in)) completeStage()
          else if (!hasBeenPulled(in)) pull(in)
        } else if (buffer.peek.elem == NotYetThere) {
          if (todo < parallelism && !hasBeenPulled(in)) tryPull(in)
        } else buffer.dequeue().elem match {
          case Success(elem) ⇒
            push(out, elem)
            if (todo < parallelism && !hasBeenPulled(in)) tryPull(in)
          case Failure(ex) ⇒ pushOne()
        }

      override def onPush(): Unit = {
        try {
          val future = f(grab(in))
          val holder = new Holder[Out](NotYetThere, futureCB)
          buffer.enqueue(holder)
          future.onComplete(holder)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(ex) ⇒ if (decider(ex) == Supervision.Stop) failStage(ex)
        }
        if (todo < parallelism) tryPull(in)
      }
      override def onUpstreamFinish(): Unit = if (todo == 0) completeStage()

      override def onPull(): Unit = pushOne()

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
private[akka] final case class MapAsyncUnordered[In, Out](parallelism: Int, f: In ⇒ Future[Out])
  extends GraphStage[FlowShape[In, Out]] {

  private val in = Inlet[In]("MapAsyncUnordered.in")
  private val out = Outlet[Out]("MapAsyncUnordered.out")

  override def initialAttributes = DefaultAttributes.mapAsyncUnordered
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      override def toString = s"MapAsyncUnordered.Logic(inFlight=$inFlight, buffer=$buffer)"

      val decider =
        inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)

      var inFlight = 0
      var buffer: BufferImpl[Out] = _
      private[this] def todo = inFlight + buffer.used

      override def preStart(): Unit = buffer = BufferImpl(parallelism, materializer)

      private val futureCB =
        getAsyncCallback((result: Try[Out]) ⇒ {
          inFlight -= 1
          result match {
            case Success(elem) if elem != null ⇒
              if (isAvailable(out)) {
                if (!hasBeenPulled(in)) tryPull(in)
                push(out, elem)
              } else buffer.enqueue(elem)
            case other ⇒
              val ex = other match {
                case Failure(t)              ⇒ t
                case Success(s) if s == null ⇒ ReactiveStreamsCompliance.elementMustNotBeNullException
              }
              if (decider(ex) == Supervision.Stop) failStage(ex)
              else if (isClosed(in) && todo == 0) completeStage()
              else if (!hasBeenPulled(in)) tryPull(in)
          }
        }).invoke _

      override def onPush(): Unit = {
        try {
          val future = f(grab(in))
          inFlight += 1
          future.onComplete(futureCB)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(ex) ⇒ if (decider(ex) == Supervision.Stop) failStage(ex)
        }
        if (todo < parallelism) tryPull(in)
      }
      override def onUpstreamFinish(): Unit = {
        if (todo == 0) completeStage()
      }

      override def onPull(): Unit = {
        if (!buffer.isEmpty) push(out, buffer.dequeue())
        else if (isClosed(in) && todo == 0) completeStage()
        if (todo < parallelism && !hasBeenPulled(in)) tryPull(in)
      }

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
private[akka] final case class Log[T](name: String, extract: T ⇒ Any,
                                      logAdapter: Option[LoggingAdapter],
                                      decider: Supervision.Decider) extends PushStage[T, T] {

  import Log._

  private var logLevels: LogLevels = _
  private var log: LoggingAdapter = _

  // TODO more optimisations can be done here - prepare logOnPush function etc

  override def preStart(ctx: LifecycleContext): Unit = {
    logLevels = ctx.attributes.get[LogLevels](DefaultLogLevels)
    log = logAdapter match {
      case Some(l) ⇒ l
      case _ ⇒
        val mat = try ActorMaterializer.downcast(ctx.materializer)
        catch {
          case ex: Exception ⇒
            throw new RuntimeException("Log stage can only provide LoggingAdapter when used with ActorMaterializer! " +
              "Provide a LoggingAdapter explicitly or use the actor based flow materializer.", ex)
        }

        Logging(mat.system, ctx)(fromLifecycleContext)
    }
  }

  override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
    if (isEnabled(logLevels.onElement))
      log.log(logLevels.onElement, "[{}] Element: {}", name, extract(elem))

    ctx.push(elem)
  }

  override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
    if (isEnabled(logLevels.onFailure))
      logLevels.onFailure match {
        case Logging.ErrorLevel ⇒ log.error(cause, "[{}] Upstream failed.", name)
        case level              ⇒ log.log(level, "[{}] Upstream failed, cause: {}: {}", name, Logging.simpleName(cause.getClass), cause.getMessage)
      }

    super.onUpstreamFailure(cause, ctx)
  }

  override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
    if (isEnabled(logLevels.onFinish))
      log.log(logLevels.onFinish, "[{}] Upstream finished.", name)

    super.onUpstreamFinish(ctx)
  }

  override def onDownstreamFinish(ctx: Context[T]): TerminationDirective = {
    if (isEnabled(logLevels.onFinish))
      log.log(logLevels.onFinish, "[{}] Downstream finished.", name)

    super.onDownstreamFinish(ctx)
  }

  private def isEnabled(l: LogLevel): Boolean = l.asInt != OffInt

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

/**
 * INTERNAL API
 */
private[akka] object Log {

  /**
   * Must be located here to be visible for implicit resolution, when LifecycleContext is passed to [[Logging]]
   * More specific LogSource than `fromString`, which would add the ActorSystem name in addition to the supervision to the log source.
   */
  final val fromLifecycleContext = new LogSource[LifecycleContext] {

    // do not expose private context classes (of OneBoundedInterpreter)
    override def getClazz(t: LifecycleContext): Class[_] = classOf[Materializer]

    override def genString(t: LifecycleContext): String = {
      try s"$DefaultLoggerName(${ActorMaterializer.downcast(t.materializer).supervisor.path})"
      catch {
        case ex: Exception ⇒ LogSource.fromString.genString(DefaultLoggerName)
      }
    }

  }

  private final val DefaultLoggerName = "akka.stream.Log"
  private final val OffInt = LogLevels.Off.asInt
  private final val DefaultLogLevels = LogLevels(onElement = Logging.DebugLevel, onFinish = Logging.DebugLevel, onFailure = Logging.ErrorLevel)
}

/**
 * INTERNAL API
 */
private[stream] object TimerKeys {
  case object TakeWithinTimerKey
  case object DropWithinTimerKey
  case object GroupedWithinTimerKey
}

private[stream] final class GroupedWithin[T](n: Int, d: FiniteDuration) extends GraphStage[FlowShape[T, immutable.Seq[T]]] {
  require(n > 0, "n must be greater than 0")
  require(d > Duration.Zero)

  val in = Inlet[T]("in")
  val out = Outlet[immutable.Seq[T]]("out")
  override def initialAttributes = DefaultAttributes.groupedWithin
  val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {

    private val buf: VectorBuilder[T] = new VectorBuilder
    // True if:
    // - buf is nonEmpty
    //       AND
    // - timer fired OR group is full
    private var groupClosed = false
    private var groupEmitted = false
    private var finished = false
    private var elements = 0

    private val GroupedWithinTimer = "GroupedWithinTimer"

    override def preStart() = {
      schedulePeriodically(GroupedWithinTimer, d)
      pull(in)
    }

    private def nextElement(elem: T): Unit = {
      groupEmitted = false
      buf += elem
      elements += 1
      if (elements == n) {
        schedulePeriodically(GroupedWithinTimer, d)
        closeGroup()
      } else pull(in)
    }

    private def closeGroup(): Unit = {
      groupClosed = true
      if (isAvailable(out)) emitGroup()
    }

    private def emitGroup(): Unit = {
      groupEmitted = true
      push(out, buf.result())
      buf.clear()
      if (!finished) startNewGroup()
      else completeStage()
    }

    private def startNewGroup(): Unit = {
      elements = 0
      groupClosed = false
      if (isAvailable(in)) nextElement(grab(in))
      else if (!hasBeenPulled(in)) pull(in)
    }

    override def onPush(): Unit = {
      if (!groupClosed) nextElement(grab(in)) // otherwise keep the element for next round
    }

    override def onPull(): Unit = if (groupClosed) emitGroup()

    override def onUpstreamFinish(): Unit = {
      finished = true
      if (groupEmitted) completeStage()
      else closeGroup()
    }

    override protected def onTimer(timerKey: Any) = if (elements > 0) closeGroup()

    setHandlers(in, out, this)
  }
}

private[stream] final class Delay[T](d: FiniteDuration, strategy: DelayOverflowStrategy) extends SimpleLinearGraphStage[T] {
  private[this] def timerName = "DelayedTimer"
  override def initialAttributes: Attributes = DefaultAttributes.delay
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    val size =
      inheritedAttributes.get[InputBuffer] match {
        case None                        ⇒ throw new IllegalStateException(s"Couldn't find InputBuffer Attribute for $this")
        case Some(InputBuffer(min, max)) ⇒ max
      }

    var buffer: BufferImpl[(Long, T)] = _ // buffer has pairs timestamp with upstream element
    var willStop = false

    override def preStart(): Unit = buffer = BufferImpl(size, materializer)

    setHandler(in, handler = new InHandler {
      //FIXME rewrite into distinct strategy functions to avoid matching on strategy for every input when full
      override def onPush(): Unit = {
        if (buffer.isFull) strategy match {
          case EmitEarly ⇒
            if (!isTimerActive(timerName))
              push(out, buffer.dequeue()._2)
            else {
              cancelTimer(timerName)
              onTimer(timerName)
            }
          case DropHead ⇒
            buffer.dropHead()
            grabAndPull(true)
          case DropTail ⇒
            buffer.dropTail()
            grabAndPull(true)
          case DropNew ⇒
            grab(in)
            if (!isTimerActive(timerName)) scheduleOnce(timerName, d)
          case DropBuffer ⇒
            buffer.clear()
            grabAndPull(true)
          case Fail ⇒
            failStage(new BufferOverflowException(s"Buffer overflow for delay combinator (max capacity was: $size)!"))
          case Backpressure ⇒ throw new IllegalStateException("Delay buffer must never overflow in Backpressure mode")
        }
        else {
          grabAndPull(strategy != Backpressure || buffer.capacity < size - 1)
          if (!isTimerActive(timerName)) scheduleOnce(timerName, d)
        }
      }

      def grabAndPull(pullCondition: Boolean): Unit = {
        buffer.enqueue((System.nanoTime(), grab(in)))
        if (pullCondition) pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        if (isAvailable(out) && isTimerActive(timerName)) willStop = true
        else completeStage()
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (!isTimerActive(timerName) && !buffer.isEmpty && nextElementWaitTime() < 0)
          push(out, buffer.dequeue()._2)

        if (!willStop && !hasBeenPulled(in)) pull(in)
        completeIfReady()
      }
    })

    def completeIfReady(): Unit = if (willStop && buffer.isEmpty) completeStage()

    def nextElementWaitTime(): Long = d.toMillis - (System.nanoTime() - buffer.peek()._1) * 1000 * 1000

    final override protected def onTimer(key: Any): Unit = {
      push(out, buffer.dequeue()._2)
      if (!buffer.isEmpty) {
        val waitTime = nextElementWaitTime()
        if (waitTime > 10) scheduleOnce(timerName, waitTime.millis)
      }
      completeIfReady()
    }
  }

  override def toString = "Delay"
}

private[stream] final class TakeWithin[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    setHandler(in, new InHandler {
      override def onPush(): Unit = push(out, grab(in))
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    final override protected def onTimer(key: Any): Unit =
      completeStage()

    override def preStart(): Unit = scheduleOnce("TakeWithinTimer", timeout)
  }

  override def toString = "TakeWithin"
}

private[stream] final class DropWithin[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

    private var allow = false

    setHandler(in, new InHandler {
      override def onPush(): Unit =
        if (allow) push(out, grab(in))
        else pull(in)
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    final override protected def onTimer(key: Any): Unit = allow = true

    override def preStart(): Unit = scheduleOnce("DropWithinTimer", timeout)
  }

  override def toString = "DropWithin"
}

/**
 * INTERNAL API
 */
private[stream] final class Reduce[T](f: (T, T) ⇒ T) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.reduce

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler { self ⇒
    override def toString = s"Reduce.Logic(aggregator=$aggregator)"

    var aggregator: T = _

    // Initial input handler
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        aggregator = grab(in)
        pull(in)
        setHandler(in, self)
      }

      override def onUpstreamFinish(): Unit =
        failStage(new NoSuchElementException("reduce over empty stream"))
    })

    override def onPush(): Unit = {
      aggregator = f(aggregator, grab(in))
      pull(in)
    }

    override def onPull(): Unit = pull(in)

    override def onUpstreamFinish(): Unit = {
      push(out, aggregator)
      completeStage()
    }

    setHandler(out, self)
  }
  override def toString = "Reduce"
}

/**
 * INTERNAL API
 */
private[stream] final class RecoverWith[T, M](pf: PartialFunction[Throwable, Graph[SourceShape[T], M]]) extends SimpleLinearGraphStage[T] {
  override def initialAttributes = DefaultAttributes.recoverWith

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
    setHandler(in, new InHandler {
      override def onPush(): Unit = push(out, grab(in))
      override def onUpstreamFailure(ex: Throwable) = onFailure(ex)
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    def onFailure(ex: Throwable) = if (pf.isDefinedAt(ex)) switchTo(pf(ex)) else failStage(ex)

    def switchTo(source: Graph[SourceShape[T], M]): Unit = {
      val sinkIn = new SubSinkInlet[T]("RecoverWithSink")
      sinkIn.setHandler(new InHandler {
        override def onPush(): Unit =
          if (isAvailable(out)) {
            push(out, sinkIn.grab())
            sinkIn.pull()
          }
        override def onUpstreamFinish(): Unit = if (!sinkIn.isAvailable) completeStage()
        override def onUpstreamFailure(ex: Throwable) = onFailure(ex)
      })

      def pushOut(): Unit = {
        push(out, sinkIn.grab())
        if (!sinkIn.isClosed) sinkIn.pull()
        else completeStage()
      }

      val outHandler = new OutHandler {
        override def onPull(): Unit = if (sinkIn.isAvailable) pushOut()
        override def onDownstreamFinish(): Unit = sinkIn.cancel()
      }

      Source.fromGraph(source).runWith(sinkIn.sink)(interpreter.subFusingMaterializer)
      setHandler(out, outHandler)
      sinkIn.pull()
    }
  }

  override def toString: String = "RecoverWith"
}

/**
 * INTERNAL API
 */
private[stream] final class StatefulMapConcat[In, Out](f: () ⇒ In ⇒ immutable.Iterable[Out]) extends GraphStage[FlowShape[In, Out]] {
  val in = Inlet[In]("StatefulMapConcat.in")
  val out = Outlet[Out]("StatefulMapConcat.out")
  override val shape = FlowShape(in, out)
  override def initialAttributes: Attributes = DefaultAttributes.statefulMapConcat

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
    var currentIterator: Iterator[Out] = _
    var plainFun = f()
    def hasNext = if (currentIterator != null) currentIterator.hasNext else false
    setHandlers(in, out, this)

    def pushPull(): Unit =
      if (hasNext) {
        push(out, currentIterator.next())
        if (!hasNext && isClosed(in)) completeStage()
      } else if (!isClosed(in))
        pull(in)
      else completeStage()

    def onFinish(): Unit = if (!hasNext) completeStage()

    override def onPush(): Unit =
      try {
        currentIterator = plainFun(grab(in)).iterator
        pushPull()
      } catch {
        case NonFatal(ex) ⇒ decider(ex) match {
          case Supervision.Stop   ⇒ failStage(ex)
          case Supervision.Resume ⇒ if (!hasBeenPulled(in)) pull(in)
          case Supervision.Restart ⇒
            restartState()
            if (!hasBeenPulled(in)) pull(in)
        }
      }

    override def onUpstreamFinish(): Unit = onFinish()
    override def onPull(): Unit = pushPull()

    private def restartState(): Unit = {
      plainFun = f()
      currentIterator = null
    }
  }
  override def toString = "StatefulMapConcat"

}
