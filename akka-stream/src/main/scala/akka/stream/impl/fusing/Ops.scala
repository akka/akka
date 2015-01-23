/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import scala.collection.immutable
import akka.stream.OverflowStrategy
import akka.stream.impl.FixedSizeBuffer
import akka.stream.stage._

/**
 * INTERNAL API
 */
private[akka] final case class Map[In, Out](f: In ⇒ Out) extends PushStage[In, Out] {
  override def onPush(elem: In, ctx: Context[Out]): Directive = ctx.push(f(elem))
}

/**
 * INTERNAL API
 */
private[akka] final case class Filter[T](p: T ⇒ Boolean) extends PushStage[T, T] {
  override def onPush(elem: T, ctx: Context[T]): Directive =
    if (p(elem)) ctx.push(elem)
    else ctx.pull()
}

private[akka] final object Collect {
  // Cached function that can be used with PartialFunction.applyOrElse to ensure that A) the guard is only applied once,
  // and the caller can check the returned value with Collect.notApplied to query whether the PF was applied or not.
  // Prior art: https://github.com/scala/scala/blob/v2.11.4/src/library/scala/collection/immutable/List.scala#L458
  final val NotApplied: Any ⇒ Any = _ ⇒ Collect.NotApplied
}

private[akka] final case class Collect[In, Out](pf: PartialFunction[In, Out]) extends PushStage[In, Out] {
  import Collect.NotApplied
  override def onPush(elem: In, ctx: Context[Out]): Directive =
    pf.applyOrElse(elem, NotApplied) match {
      case NotApplied             ⇒ ctx.pull()
      case result: Out @unchecked ⇒ ctx.push(result)
    }
}

/**
 * INTERNAL API
 */
private[akka] final case class MapConcat[In, Out](f: In ⇒ immutable.Seq[Out]) extends PushPullStage[In, Out] {
  private var currentIterator: Iterator[Out] = Iterator.empty

  override def onPush(elem: In, ctx: Context[Out]): Directive = {
    currentIterator = f(elem).iterator
    if (currentIterator.isEmpty) ctx.pull()
    else ctx.push(currentIterator.next())
  }

  override def onPull(ctx: Context[Out]): Directive =
    if (currentIterator.hasNext) ctx.push(currentIterator.next())
    else if (ctx.isFinishing) ctx.finish()
    else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective =
    ctx.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] final case class Take[T](count: Int) extends PushStage[T, T] {
  private var left: Int = count

  override def onPush(elem: T, ctx: Context[T]): Directive = {
    left -= 1
    if (left > 0) ctx.push(elem)
    else if (left == 0) ctx.pushAndFinish(elem)
    else ctx.finish() //Handle negative take counts
  }
}

/**
 * INTERNAL API
 */
private[akka] final case class Drop[T](count: Int) extends PushStage[T, T] {
  private var left: Int = count
  override def onPush(elem: T, ctx: Context[T]): Directive =
    if (left > 0) {
      left -= 1
      ctx.pull()
    } else ctx.push(elem)
}

/**
 * INTERNAL API
 */
private[akka] final case class Scan[In, Out](zero: Out, f: (Out, In) ⇒ Out) extends PushPullStage[In, Out] {
  private var aggregator = zero

  override def onPush(elem: In, ctx: Context[Out]): Directive = {
    val old = aggregator
    aggregator = f(old, elem)
    ctx.push(old)
  }

  override def onPull(ctx: Context[Out]): Directive =
    if (ctx.isFinishing) ctx.pushAndFinish(aggregator)
    else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective = ctx.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] final case class Fold[In, Out](zero: Out, f: (Out, In) ⇒ Out) extends PushPullStage[In, Out] {
  private var aggregator = zero

  override def onPush(elem: In, ctx: Context[Out]): Directive = {
    aggregator = f(aggregator, elem)
    ctx.pull()
  }

  override def onPull(ctx: Context[Out]): Directive =
    if (ctx.isFinishing) ctx.pushAndFinish(aggregator)
    else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective = ctx.absorbTermination()
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

  override def onPush(elem: T, ctx: Context[immutable.Seq[T]]): Directive = {
    buf += elem
    left -= 1
    if (left == 0) {
      val emit = buf.result()
      buf.clear()
      left = n
      ctx.push(emit)
    } else ctx.pull()
  }

  override def onPull(ctx: Context[immutable.Seq[T]]): Directive =
    if (ctx.isFinishing) {
      val elem = buf.result()
      buf.clear() //FIXME null out the reference to the `buf`?
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
private[akka] final case class Buffer[T](size: Int, overflowStrategy: OverflowStrategy) extends DetachedStage[T, T] {
  import OverflowStrategy._

  private val buffer = FixedSizeBuffer(size)

  override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective =
    if (ctx.isHolding) ctx.pushAndPull(elem)
    else enqueueAction(ctx, elem)

  override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
    if (ctx.isFinishing) {
      val elem = buffer.dequeue().asInstanceOf[T]
      if (buffer.isEmpty) ctx.pushAndFinish(elem)
      else ctx.push(elem)
    } else if (ctx.isHolding) ctx.pushAndPull(buffer.dequeue().asInstanceOf[T])
    else if (buffer.isEmpty) ctx.hold()
    else ctx.push(buffer.dequeue().asInstanceOf[T])
  }

  override def onUpstreamFinish(ctx: DetachedContext[T]): TerminationDirective =
    if (buffer.isEmpty) ctx.finish()
    else ctx.absorbTermination()

  val enqueueAction: (DetachedContext[T], T) ⇒ UpstreamDirective = {
    overflowStrategy match {
      case DropHead ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) buffer.dropHead()
        buffer.enqueue(elem)
        ctx.pull()
      }
      case DropTail ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) buffer.dropTail()
        buffer.enqueue(elem)
        ctx.pull()
      }
      case DropBuffer ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) buffer.clear()
        buffer.enqueue(elem)
        ctx.pull()
      }
      case Backpressure ⇒ { (ctx, elem) ⇒
        buffer.enqueue(elem)
        if (buffer.isFull) ctx.hold()
        else ctx.pull()
      }
      case Error ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) ctx.fail(new Error.BufferOverflowException(s"Buffer overflow (max capacity was: $size)!"))
        else {
          buffer.enqueue(elem)
          ctx.pull()
        }
      }
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] final case class Completed[T]() extends PushPullStage[T, T] {
  override def onPush(elem: T, ctx: Context[T]): Directive = ctx.finish()
  override def onPull(ctx: Context[T]): Directive = ctx.finish()
}

/**
 * INTERNAL API
 */
private[akka] final case class Conflate[In, Out](seed: In ⇒ Out, aggregate: (Out, In) ⇒ Out) extends DetachedStage[In, Out] {
  private var agg: Any = null

  override def onPush(elem: In, ctx: DetachedContext[Out]): UpstreamDirective = {
    agg = if (agg == null) seed(elem)
    else aggregate(agg.asInstanceOf[Out], elem)

    if (!ctx.isHolding) ctx.pull()
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
    } else if (agg == null) ctx.hold()
    else {
      val result = agg.asInstanceOf[Out]
      agg = null
      ctx.push(result)
    }
  }

  override def onUpstreamFinish(ctx: DetachedContext[Out]): TerminationDirective = ctx.absorbTermination()
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
    if (ctx.isHolding) {
      val (emit, newS) = extrapolate(s)
      s = newS
      expanded = true
      ctx.pushAndPull(emit)
    } else ctx.hold()
  }

  override def onPull(ctx: DetachedContext[Out]): DownstreamDirective = {
    if (ctx.isFinishing) {
      if (!started) ctx.finish()
      else ctx.pushAndFinish(extrapolate(s)._1)
    } else if (!started) ctx.hold()
    else {
      val (emit, newS) = extrapolate(s)
      s = newS
      expanded = true
      if (ctx.isHolding) ctx.pushAndPull(emit)
      else ctx.push(emit)
    }

  }

  override def onUpstreamFinish(ctx: DetachedContext[Out]): TerminationDirective = {
    if (expanded) ctx.finish()
    else ctx.absorbTermination()
  }
}
