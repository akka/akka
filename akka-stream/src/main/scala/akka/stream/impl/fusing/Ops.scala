/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.event.Logging.LogLevel
import akka.event.{ LogSource, Logging, LoggingAdapter }
import akka.stream.Attributes.LogLevels
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.impl.{ FixedSizeBuffer, ReactiveStreamsCompliance }
import akka.stream.stage._
import akka.stream.{ Supervision, _ }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

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
private[akka] final case class TakeWhile[T](p: T ⇒ Boolean, decider: Supervision.Decider) extends PushStage[T, T] {

  override def onPush(elem: T, ctx: Context[T]): SyncDirective =
    if (p(elem))
      ctx.push(elem)
    else
      ctx.finish()

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

/**
 * INTERNAL API
 */
private[akka] final case class DropWhile[T](p: T ⇒ Boolean, decider: Supervision.Decider) extends PushStage[T, T] {
  var taking = false

  override def onPush(elem: T, ctx: Context[T]): SyncDirective =
    if (taking || !p(elem)) {
      taking = true
      ctx.push(elem)
    } else {
      ctx.pull()
    }

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

private[akka] object Collect {
  // Cached function that can be used with PartialFunction.applyOrElse to ensure that A) the guard is only applied once,
  // and the caller can check the returned value with Collect.notApplied to query whether the PF was applied or not.
  // Prior art: https://github.com/scala/scala/blob/v2.11.4/src/library/scala/collection/immutable/List.scala#L458
  final val NotApplied: Any ⇒ Any = _ ⇒ Collect.NotApplied
}

private[akka] final case class Collect[In, Out](pf: PartialFunction[In, Out], decider: Supervision.Decider) extends PushStage[In, Out] {

  import Collect.NotApplied

  override def onPush(elem: In, ctx: Context[Out]): SyncDirective =
    pf.applyOrElse(elem, NotApplied) match {
      case NotApplied             ⇒ ctx.pull()
      case result: Out @unchecked ⇒ ctx.push(result)
    }

  override def decide(t: Throwable): Supervision.Directive = decider(t)
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
private[akka] final case class MapConcat[In, Out](f: In ⇒ immutable.Iterable[Out], decider: Supervision.Decider) extends PushPullStage[In, Out] {
  private var currentIterator: Iterator[Out] = Iterator.empty

  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = {
    currentIterator = f(elem).iterator
    if (!currentIterator.hasNext) ctx.pull()
    else ctx.push(currentIterator.next())
  }

  override def onPull(ctx: Context[Out]): SyncDirective =
    if (ctx.isFinishing) {
      if (currentIterator.hasNext) {
        val elem = currentIterator.next()
        if (currentIterator.hasNext) ctx.push(elem)
        else ctx.pushAndFinish(elem)
      } else ctx.finish()
    } else {
      if (currentIterator.hasNext) ctx.push(currentIterator.next())
      else ctx.pull()
    }

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective =
    if (currentIterator.hasNext) ctx.absorbTermination()
    else ctx.finish()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): MapConcat[In, Out] = copy()
}

/**
 * INTERNAL API
 */
private[akka] final case class Take[T](count: Long) extends PushPullStage[T, T] {
  private var left: Long = count

  override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
    left -= 1
    if (left > 0) ctx.push(elem)
    else if (left == 0) ctx.pushAndFinish(elem)
    else ctx.finish() //Handle negative take counts
  }

  override def onPull(ctx: Context[T]): SyncDirective =
    if (left <= 0) ctx.finish()
    else ctx.pull()
}

/**
 * INTERNAL API
 */
private[akka] final case class Drop[T](count: Long) extends PushStage[T, T] {
  private var left: Long = count

  override def onPush(elem: T, ctx: Context[T]): SyncDirective =
    if (left > 0) {
      left -= 1
      ctx.pull()
    } else ctx.push(elem)
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
private[akka] final case class Intersperse[T](start: Option[T], inject: T, end: Option[T]) extends StatefulStage[T, T] {
  private var needsToEmitStart = start.isDefined

  override def initial: StageState[T, T] =
    start match {
      case Some(initial) ⇒ firstWithInitial(initial)
      case _             ⇒ first
    }

  def firstWithInitial(initial: T) = new StageState[T, T] {
    override def onPush(elem: T, ctx: Context[T]) = {
      needsToEmitStart = false
      emit(Iterator(initial, elem), ctx, running)
    }
  }

  def first = new StageState[T, T] {
    override def onPush(elem: T, ctx: Context[T]) = {
      become(running)
      ctx.push(elem)
    }
  }

  def running = new StageState[T, T] {
    override def onPush(elem: T, ctx: Context[T]): SyncDirective =
      emit(Iterator(inject, elem), ctx)
  }

  override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
    end match {
      case Some(e) if needsToEmitStart ⇒
        terminationEmit(Iterator(start.get, end.get), ctx)
      case Some(e) ⇒
        terminationEmit(Iterator(end.get), ctx)
      case _ ⇒
        terminationEmit(Iterator(), ctx)
    }
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
private[akka] final case class Sliding[T](n: Int, step: Int) extends PushPullStage[T, immutable.Seq[T]] {
  private val buf = {
    val b = Vector.newBuilder[T]
    b.sizeHint(n)
    b
  }
  var bufferedElements = 0

  override def onPush(elem: T, ctx: Context[immutable.Seq[T]]): SyncDirective = {
    buf += elem
    bufferedElements += 1
    if (bufferedElements < n) {
      ctx.pull()
    } else if (bufferedElements == n) {
      ctx.push(buf.result())
    } else {
      if (step > n) {
        if (bufferedElements == step) {
          buf.clear()
          buf.sizeHint(n)
          bufferedElements = 0
          ctx.pull()
        } else {
          ctx.pull()
        }
      } else {
        val emit = buf.result()
        buf.clear()
        buf.sizeHint(n)
        emit.drop(step).foreach(buf += _)
        val updatedEmit = buf.result()
        bufferedElements = updatedEmit.size
        if (bufferedElements == n) ctx.push(updatedEmit)
        else ctx.pull()
      }
    }
  }

  override def onPull(ctx: Context[immutable.Seq[T]]): SyncDirective =
    if (ctx.isFinishing) {
      val emit = buf.result()
      if (emit.size == n) {
        ctx.finish()
      } else {
        ctx.pushAndFinish(emit)
      }
    } else ctx.pull()

  override def onUpstreamFinish(ctx: Context[immutable.Seq[T]]): TerminationDirective =
    if (buf.result().isEmpty) ctx.finish()
    else ctx.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] final case class Buffer[T](size: Int, overflowStrategy: OverflowStrategy) extends DetachedStage[T, T] {

  import OverflowStrategy._

  private val buffer = FixedSizeBuffer[T](size)

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

  val enqueueAction: (DetachedContext[T], T) ⇒ UpstreamDirective = {
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
        if (buffer.isFull) ctx.fail(new Fail.BufferOverflowException(s"Buffer overflow (max capacity was: $size)!"))
        else {
          buffer.enqueue(elem)
          ctx.pull()
        }
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
private[akka] final case class Conflate[In, Out](seed: In ⇒ Out, aggregate: (Out, In) ⇒ Out,
                                                 decider: Supervision.Decider) extends DetachedStage[In, Out] {
  private var agg: Any = null

  override def onPush(elem: In, ctx: DetachedContext[Out]): UpstreamDirective = {
    agg =
      if (agg == null) seed(elem)
      else aggregate(agg.asInstanceOf[Out], elem)

    if (!ctx.isHoldingDownstream) ctx.pull()
    else {
      val result = agg.asInstanceOf[Out]
      agg = null
      ctx.pushAndPull(result)
    }
  }

  override def onPull(ctx: DetachedContext[Out]): DownstreamDirective = {
    if (ctx.isFinishing) {
      if (agg == null) ctx.finish()
      else {
        val result = agg.asInstanceOf[Out]
        agg = null
        ctx.pushAndFinish(result)
      }
    } else if (agg == null) ctx.holdDownstream()
    else {
      val result = agg.asInstanceOf[Out]
      if (result == null) throw new NullPointerException
      agg = null
      ctx.push(result)
    }
  }

  override def onUpstreamFinish(ctx: DetachedContext[Out]): TerminationDirective = ctx.absorbTermination()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): Conflate[In, Out] = copy()
}

/**
 * INTERNAL API
 */
private[akka] final case class Expand[In, Out, Seed](seed: In ⇒ Seed, extrapolate: Seed ⇒ (Out, Seed)) extends DetachedStage[In, Out] {
  private var s: Seed = _
  private var started: Boolean = false
  private var expanded: Boolean = false

  override def onPush(elem: In, ctx: DetachedContext[Out]): UpstreamDirective = {
    s = seed(elem)
    started = true
    expanded = false
    if (ctx.isHoldingDownstream) {
      val (emit, newS) = extrapolate(s)
      s = newS
      expanded = true
      ctx.pushAndPull(emit)
    } else ctx.holdUpstream()
  }

  override def onPull(ctx: DetachedContext[Out]): DownstreamDirective = {
    if (ctx.isFinishing) {
      if (!started) ctx.finish()
      else ctx.pushAndFinish(extrapolate(s)._1)
    } else if (!started) ctx.holdDownstream()
    else {
      val (emit, newS) = extrapolate(s)
      s = newS
      expanded = true
      if (ctx.isHoldingUpstream) ctx.pushAndPull(emit)
      else ctx.push(emit)
    }

  }

  override def onUpstreamFinish(ctx: DetachedContext[Out]): TerminationDirective = {
    if (expanded) ctx.finish()
    else ctx.absorbTermination()
  }

  override def decide(t: Throwable): Supervision.Directive = Supervision.Stop

  override def restart(): Expand[In, Out, Seed] =
    throw new UnsupportedOperationException("Expand doesn't support restart")
}

/**
 * INTERNAL API
 */
private[akka] object MapAsync {
  val NotYetThere = Failure(new Exception)
}

/**
 * INTERNAL API
 */
private[akka] final case class MapAsync[In, Out](parallelism: Int, f: In ⇒ Future[Out], decider: Supervision.Decider)
  extends AsyncStage[In, Out, (Int, Try[Out])] {

  import MapAsync._

  type Notification = (Int, Try[Out])

  private var callback: AsyncCallback[Notification] = _
  private val elemsInFlight = FixedSizeBuffer[Try[Out]](parallelism)

  override def preStart(ctx: AsyncContext[Out, Notification]): Unit = {
    callback = ctx.getAsyncCallback
  }

  override def decide(ex: Throwable) = decider(ex)

  override def onPush(elem: In, ctx: AsyncContext[Out, Notification]) = {
    val future = f(elem)
    val idx = elemsInFlight.enqueue(NotYetThere)
    future.onComplete(t ⇒ callback.invoke((idx, t)))(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
    if (elemsInFlight.isFull) ctx.holdUpstream()
    else ctx.pull()
  }

  override def onPull(ctx: AsyncContext[Out, (Int, Try[Out])]) = {
    @tailrec def rec(): DownstreamDirective =
      if (elemsInFlight.isEmpty && ctx.isFinishing) ctx.finish()
      else if (elemsInFlight.isEmpty || elemsInFlight.peek == NotYetThere) {
        if (!elemsInFlight.isFull && ctx.isHoldingUpstream) ctx.holdDownstreamAndPull()
        else ctx.holdDownstream()
      } else elemsInFlight.dequeue() match {
        case Failure(ex) ⇒ rec()
        case Success(elem) ⇒
          if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
          else ctx.push(elem)
      }
    rec()
  }

  override def onAsyncInput(input: (Int, Try[Out]), ctx: AsyncContext[Out, Notification]) = {
    @tailrec def rec(): Directive =
      if (elemsInFlight.isEmpty && ctx.isFinishing) ctx.finish()
      else if (elemsInFlight.isEmpty || (elemsInFlight.peek eq NotYetThere)) {
        if (!elemsInFlight.isFull && ctx.isHoldingUpstream) ctx.pull()
        else ctx.ignore()
      } else elemsInFlight.dequeue() match {
        case Failure(ex) ⇒ rec()
        case Success(elem) ⇒
          if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
          else ctx.push(elem)
      }

    input match {
      case (idx, f @ Failure(ex)) ⇒
        if (decider(ex) != Supervision.Stop) {
          elemsInFlight.put(idx, f)
          if (ctx.isHoldingDownstream) rec()
          else ctx.ignore()
        } else ctx.fail(ex)
      case (idx, s: Success[_]) ⇒
        val exception = try {
          ReactiveStreamsCompliance.requireNonNullElement(s.value)
          elemsInFlight.put(idx, s)
          null: Exception
        } catch {
          case NonFatal(ex) ⇒
            if (decider(ex) != Supervision.Stop) {
              elemsInFlight.put(idx, Failure(ex))
              null: Exception
            } else ex
        }
        if (exception != null) ctx.fail(exception)
        else if (ctx.isHoldingDownstream) rec()
        else ctx.ignore()
    }
  }

  override def onUpstreamFinish(ctx: AsyncContext[Out, Notification]) =
    if (ctx.isHoldingUpstream || !elemsInFlight.isEmpty) ctx.absorbTermination()
    else ctx.finish()
}

/**
 * INTERNAL API
 */
private[akka] final case class MapAsyncUnordered[In, Out](parallelism: Int, f: In ⇒ Future[Out], decider: Supervision.Decider)
  extends AsyncStage[In, Out, Try[Out]] {

  private var callback: AsyncCallback[Try[Out]] = _
  private var inFlight = 0
  private val buffer = FixedSizeBuffer[Out](parallelism)

  private def todo = inFlight + buffer.used

  override def preStart(ctx: AsyncContext[Out, Try[Out]]): Unit =
    callback = ctx.getAsyncCallback

  override def decide(ex: Throwable) = decider(ex)

  override def onPush(elem: In, ctx: AsyncContext[Out, Try[Out]]) = {
    val future = f(elem)
    inFlight += 1
    future.onComplete(callback.invoke)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
    if (todo == parallelism) ctx.holdUpstream()
    else ctx.pull()
  }

  override def onPull(ctx: AsyncContext[Out, Try[Out]]) =
    if (buffer.isEmpty) {
      if (ctx.isFinishing && inFlight == 0) ctx.finish() else ctx.holdDownstream()
    } else {
      val elem = buffer.dequeue()
      if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
      else ctx.push(elem)
    }

  override def onAsyncInput(input: Try[Out], ctx: AsyncContext[Out, Try[Out]]) = {
    def ignoreOrFail(ex: Throwable) =
      if (decider(ex) == Supervision.Stop) ctx.fail(ex)
      else if (ctx.isHoldingUpstream) ctx.pull()
      else if (ctx.isFinishing && todo == 0) ctx.finish()
      else ctx.ignore()

    inFlight -= 1
    input match {
      case Failure(ex) ⇒ ignoreOrFail(ex)
      case Success(elem) ⇒
        if (elem == null) {
          val ex = ReactiveStreamsCompliance.elementMustNotBeNullException
          ignoreOrFail(ex)
        } else if (ctx.isHoldingDownstream) {
          if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
          else ctx.push(elem)
        } else {
          buffer.enqueue(elem)
          ctx.ignore()
        }
    }
  }

  override def onUpstreamFinish(ctx: AsyncContext[Out, Try[Out]]) =
    if (todo > 0) ctx.absorbTermination()
    else ctx.finish()
}

/**
 * INTERNAL API
 */
private[akka] final case class Log[T](name: String, extract: T ⇒ Any, logAdapter: Option[LoggingAdapter]) extends PushStage[T, T] {

  import Log._

  private var logLevels: LogLevels = _
  private var log: LoggingAdapter = _

  // TODO more optimisations can be done here - prepare logOnPush function etc

  override def preStart(ctx: LifecycleContext): Unit = {
    logLevels = ctx.attributes.logLevels.getOrElse(DefaultLogLevels)
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

private[stream] class GroupedWithin[T](n: Int, d: FiniteDuration) extends GraphStage[FlowShape[T, immutable.Seq[T]]] {
  val in = Inlet[T]("in")
  val out = Outlet[immutable.Seq[T]]("out")
  val shape = FlowShape(in, out)

  override def createLogic: GraphStageLogic = new GraphStageLogic(shape) {
    private val buf: VectorBuilder[T] = new VectorBuilder
    // True if:
    // - buf is nonEmpty
    //       AND
    // - timer fired OR group is full
    private var groupClosed = false
    private var finished = false
    private var elements = 0

    private val GroupedWithinTimer = "GroupedWithinTimer"

    override def preStart() = {
      schedulePeriodically(GroupedWithinTimer, d)
      pull(in)
    }

    private def nextElement(elem: T): Unit = {
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

    setHandler(in, new InHandler {
      override def onPush(): Unit =
        if (!groupClosed) nextElement(grab(in)) // otherwise keep the element for next round
      override def onUpstreamFinish(): Unit = {
        finished = true
        if (!groupClosed && elements > 0) closeGroup()
        else completeStage()
      }
      override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = if (groupClosed) emitGroup()
      override def onDownstreamFinish(): Unit = completeStage()
    })

    override protected def onTimer(timerKey: Any) =
      if (elements > 0) closeGroup()
  }
}

private[stream] class TakeWithin[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {

  override def createLogic: GraphStageLogic = new SimpleLinearStageLogic {
    setHandler(in, new InHandler {
      override def onPush(): Unit = push(out, grab(in))
      override def onUpstreamFinish(): Unit = completeStage()
      override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
    })

    final override protected def onTimer(key: Any): Unit =
      completeStage()

    override def preStart(): Unit = scheduleOnce("TakeWithinTimer", timeout)
  }

  override def toString = "TakeWithin"
}

private[stream] class DropWithin[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {
  private var allow = false

  override def createLogic: GraphStageLogic = new SimpleLinearStageLogic {
    setHandler(in, new InHandler {
      override def onPush(): Unit =
        if (allow) push(out, grab(in))
        else pull(in)
      override def onUpstreamFinish(): Unit = completeStage()
      override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
    })

    final override protected def onTimer(key: Any): Unit = allow = true

    override def preStart(): Unit = scheduleOnce("DropWithinTimer", timeout)
  }

  override def toString = "DropWithin"
}
