/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import java.util.function.Consumer
import java.util.function.{ Function => JFunction }

import scala.annotation.tailrec
import scala.util.control.NonFatal

import akka.actor.typed.Behavior
import akka.actor.typed.Signal
import akka.actor.typed.TypedActorContext
import akka.actor.typed.javadsl
import akka.actor.typed.scaladsl
import akka.annotation.InternalApi
import akka.util.ConstantFun

/**
 * INTERNAL API
 */
@InternalApi private[akka] object StashBufferImpl {
  private final class Node[T](var next: Node[T], val message: T) {
    def apply(f: T => Unit): Unit = f(message)
  }

  def apply[T](capacity: Int): StashBufferImpl[T] =
    new StashBufferImpl(capacity, null, null)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class StashBufferImpl[T] private (
    val capacity: Int,
    private var _first: StashBufferImpl.Node[T],
    private var _last: StashBufferImpl.Node[T])
    extends javadsl.StashBuffer[T]
    with scaladsl.StashBuffer[T] {

  import StashBufferImpl.Node

  private var _size: Int = if (_first eq null) 0 else 1

  override def isEmpty: Boolean = _first eq null

  override def nonEmpty: Boolean = !isEmpty

  override def size: Int = _size

  override def isFull: Boolean = _size == capacity

  override def stash(message: T): StashBufferImpl[T] = {
    if (message == null) throw new NullPointerException
    if (isFull)
      throw new javadsl.StashOverflowException(
        s"Couldn't add [${message.getClass.getName}] " +
        s"because stash with capacity [$capacity] is full")

    val node = new Node(null, message)
    if (isEmpty) {
      _first = node
      _last = node
    } else {
      _last.next = node
      _last = node
    }
    _size += 1
    this
  }

  private def dropHead(): T = {
    val message = head
    _first = _first.next
    _size -= 1
    if (isEmpty)
      _last = null

    message
  }

  override def head: T =
    if (nonEmpty) _first.message
    else throw new NoSuchElementException("head of empty buffer")

  override def foreach(f: T => Unit): Unit = {
    var node = _first
    while (node ne null) {
      node(f)
      node = node.next
    }
  }

  override def forEach(f: Consumer[T]): Unit = foreach(f.accept)

  override def unstashAll(ctx: scaladsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    unstash(ctx, behavior, size, ConstantFun.scalaIdentityFunction[T])

  override def unstashAll(ctx: javadsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    unstashAll(ctx.asScala, behavior)

  override def unstash(
      ctx: scaladsl.ActorContext[T],
      behavior: Behavior[T],
      numberOfMessages: Int,
      wrap: T => T): Behavior[T] = {
    if (isEmpty)
      behavior // optimization
    else {
      val iter = new Iterator[T] {
        override def hasNext: Boolean = StashBufferImpl.this.nonEmpty
        override def next(): T = wrap(StashBufferImpl.this.dropHead())
      }.take(numberOfMessages)
      interpretUnstashedMessages(behavior, ctx, iter)
    }
  }

  private def interpretUnstashedMessages(
      behavior: Behavior[T],
      ctx: TypedActorContext[T],
      messages: Iterator[T]): Behavior[T] = {
    @tailrec def interpretOne(b: Behavior[T]): Behavior[T] = {
      val b2 = Behavior.start(b, ctx)
      if (!Behavior.isAlive(b2) || !messages.hasNext) b2
      else {
        val nextB = try {
          messages.next() match {
            case sig: Signal => Behavior.interpretSignal(b2, ctx, sig)
            case msg         => Behavior.interpretMessage(b2, ctx, msg)
          }
        } catch {
          case NonFatal(e) => throw UnstashException(e, b2)
        }

        interpretOne(Behavior.canonicalize(nextB, b2, ctx)) // recursive
      }
    }

    interpretOne(Behavior.start(behavior, ctx))
  }

  override def unstash(
      ctx: javadsl.ActorContext[T],
      behavior: Behavior[T],
      numberOfMessages: Int,
      wrap: JFunction[T, T]): Behavior[T] =
    unstash(ctx.asScala, behavior, numberOfMessages, x => wrap.apply(x))

  override def toString: String =
    s"StashBuffer($size/$capacity)"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object UnstashException {
  def unwrap(t: Throwable): Throwable = t match {
    case UnstashException(e, _) => e
    case _                      => t
  }

}

/**
 * INTERNAL API:
 *
 * When unstashing, the exception is wrapped in UnstashException because supervisor strategy
 * and ActorAdapter need the behavior that threw. It will use the behavior in the `UnstashException`
 * to emit the PreRestart and PostStop to the right behavior and install the latest behavior for resume strategy.
 */
@InternalApi private[akka] final case class UnstashException[T](cause: Throwable, behavior: Behavior[T])
    extends RuntimeException(s"[$cause] when unstashing in [$behavior]", cause)
