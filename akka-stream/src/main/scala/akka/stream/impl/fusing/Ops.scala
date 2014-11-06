/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.OverflowStrategy
import akka.stream.impl.FixedSizeBuffer

import scala.collection.immutable

/**
 * INTERNAL API
 */
private[akka] case class Map[In, Out](f: In ⇒ Out) extends TransitivePullOp[In, Out] {
  override def onPush(elem: In, ctxt: Context[Out]): Directive = ctxt.push(f(elem))
}

/**
 * INTERNAL API
 */
private[akka] case class Filter[T](p: T ⇒ Boolean) extends TransitivePullOp[T, T] {
  override def onPush(elem: T, ctxt: Context[T]): Directive =
    if (p(elem)) ctxt.push(elem)
    else ctxt.pull()
}

/**
 * INTERNAL API
 */
private[akka] case class MapConcat[In, Out](f: In ⇒ immutable.Seq[Out]) extends DeterministicOp[In, Out] {
  private var currentIterator: Iterator[Out] = Iterator.empty

  override def onPush(elem: In, ctxt: Context[Out]): Directive = {
    currentIterator = f(elem).iterator
    if (currentIterator.isEmpty) ctxt.pull()
    else ctxt.push(currentIterator.next())
  }

  override def onPull(ctxt: Context[Out]): Directive =
    if (currentIterator.hasNext) ctxt.push(currentIterator.next())
    else ctxt.pull()
}

/**
 * INTERNAL API
 */
private[akka] case class Take[T](count: Int) extends TransitivePullOp[T, T] {
  private var left: Int = count

  override def onPush(elem: T, ctxt: Context[T]): Directive = {
    left -= 1
    if (left == 0) ctxt.pushAndFinish(elem)
    else ctxt.push(elem)
  }
}

/**
 * INTERNAL API
 */
private[akka] case class Drop[T](count: Int) extends TransitivePullOp[T, T] {
  private var left: Int = count
  override def onPush(elem: T, ctxt: Context[T]): Directive =
    if (left > 0) {
      left -= 1
      ctxt.pull()
    } else ctxt.push(elem)
}

/**
 * INTERNAL API
 */
private[akka] case class Fold[In, Out](zero: Out, f: (Out, In) ⇒ Out) extends DeterministicOp[In, Out] {
  private var aggregator = zero

  override def onPush(elem: In, ctxt: Context[Out]): Directive = {
    aggregator = f(aggregator, elem)
    ctxt.pull()
  }

  override def onPull(ctxt: Context[Out]): Directive =
    if (isFinishing) ctxt.pushAndFinish(aggregator)
    else ctxt.pull()

  override def onUpstreamFinish(ctxt: Context[Out]): Directive = ctxt.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] case class Grouped[T](n: Int) extends DeterministicOp[T, immutable.Seq[T]] {
  private var buf: Vector[T] = Vector.empty

  override def onPush(elem: T, ctxt: Context[immutable.Seq[T]]): Directive = {
    buf :+= elem
    if (buf.size == n) {
      val emit = buf
      buf = Vector.empty
      ctxt.push(emit)
    } else ctxt.pull()
  }

  override def onPull(ctxt: Context[immutable.Seq[T]]): Directive =
    if (isFinishing) ctxt.pushAndFinish(buf)
    else ctxt.pull()

  override def onUpstreamFinish(ctxt: Context[immutable.Seq[T]]): Directive =
    if (buf.isEmpty) ctxt.finish()
    else ctxt.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] case class Buffer[T](size: Int, overflowStrategy: OverflowStrategy) extends DetachedOp[T, T] {
  import OverflowStrategy._

  private val buffer = FixedSizeBuffer(size)

  override def onPush(elem: T, ctxt: DetachedContext[T]): UpstreamDirective =
    if (isHolding) ctxt.pushAndPull(elem)
    else enqueueAction(ctxt, elem)

  override def onPull(ctxt: DetachedContext[T]): DownstreamDirective = {
    if (isFinishing) {
      val elem = buffer.dequeue().asInstanceOf[T]
      if (buffer.isEmpty) ctxt.pushAndFinish(elem)
      else ctxt.push(elem)
    } else if (isHolding) ctxt.pushAndPull(buffer.dequeue().asInstanceOf[T])
    else if (buffer.isEmpty) ctxt.hold()
    else ctxt.push(buffer.dequeue().asInstanceOf[T])
  }

  override def onUpstreamFinish(ctxt: DetachedContext[T]): Directive =
    if (buffer.isEmpty) ctxt.finish()
    else ctxt.absorbTermination()

  val enqueueAction: (DetachedContext[T], T) ⇒ UpstreamDirective = {
    overflowStrategy match {
      case DropHead ⇒ { (ctxt, elem) ⇒
        if (buffer.isFull) buffer.dropHead()
        buffer.enqueue(elem)
        ctxt.pull()
      }
      case DropTail ⇒ { (ctxt, elem) ⇒
        if (buffer.isFull) buffer.dropTail()
        buffer.enqueue(elem)
        ctxt.pull()
      }
      case DropBuffer ⇒ { (ctxt, elem) ⇒
        if (buffer.isFull) buffer.clear()
        buffer.enqueue(elem)
        ctxt.pull()
      }
      case Backpressure ⇒ { (ctxt, elem) ⇒
        buffer.enqueue(elem)
        if (buffer.isFull) ctxt.hold()
        else ctxt.pull()
      }
      case Error ⇒ { (ctxt, elem) ⇒
        if (buffer.isFull) ctxt.fail(new Error.BufferOverflowException(s"Buffer overflow (max capacity was: $size)!"))
        else {
          buffer.enqueue(elem)
          ctxt.pull()
        }
      }
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] case class Completed[T]() extends DeterministicOp[T, T] {
  override def onPush(elem: T, ctxt: Context[T]): Directive = ctxt.finish()
  override def onPull(ctxt: Context[T]): Directive = ctxt.finish()
}

/**
 * INTERNAL API
 */
private[akka] case class Conflate[In, Out](seed: In ⇒ Out, aggregate: (Out, In) ⇒ Out) extends DetachedOp[In, Out] {
  private var agg: Any = null

  override def onPush(elem: In, ctxt: DetachedContext[Out]): UpstreamDirective = {
    if (agg == null) agg = seed(elem)
    else agg = aggregate(agg.asInstanceOf[Out], elem)

    if (!isHolding) ctxt.pull() else {
      val result = agg.asInstanceOf[Out]
      agg = null
      ctxt.pushAndPull(result)
    }
  }

  override def onPull(ctxt: DetachedContext[Out]): DownstreamDirective = {
    if (isFinishing) {
      if (agg == null) ctxt.finish()
      else {
        val result = agg.asInstanceOf[Out]
        agg = null
        ctxt.pushAndFinish(result)
      }
    } else if (agg == null) ctxt.hold()
    else {
      val result = agg.asInstanceOf[Out]
      agg = null
      ctxt.push(result)
    }
  }

  override def onUpstreamFinish(ctxt: DetachedContext[Out]): Directive = ctxt.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] case class Expand[In, Out, Seed](seed: In ⇒ Seed, extrapolate: Seed ⇒ (Out, Seed)) extends DetachedOp[In, Out] {
  private var s: Any = null

  override def onPush(elem: In, ctxt: DetachedContext[Out]): UpstreamDirective = {
    s = seed(elem)
    if (isHolding) {
      val (emit, newS) = extrapolate(s.asInstanceOf[Seed])
      s = newS
      ctxt.pushAndPull(emit)
    } else ctxt.hold()
  }

  override def onPull(ctxt: DetachedContext[Out]): DownstreamDirective = {
    if (s == null) ctxt.hold()
    else {
      val (emit, newS) = extrapolate(s.asInstanceOf[Seed])
      s = newS
      if (isHolding) {
        ctxt.pushAndPull(emit)
      } else ctxt.push(emit)
    }

  }
}