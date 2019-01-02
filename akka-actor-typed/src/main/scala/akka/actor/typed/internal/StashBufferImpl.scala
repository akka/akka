/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import java.util.function.Consumer
import java.util.function.{ Function ⇒ JFunction }

import akka.actor.typed.Behavior
import akka.actor.typed.javadsl
import akka.actor.typed.scaladsl
import akka.annotation.InternalApi
import akka.util.ConstantFun

/**
 * INTERNAL API
 */
@InternalApi private[akka] object StashBufferImpl {
  private final class Node[T](var next: Node[T], val message: T) {
    def apply(f: T ⇒ Unit): Unit = f(message)
  }

  def apply[T](capacity: Int): StashBufferImpl[T] =
    new StashBufferImpl(capacity, null, null)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class StashBufferImpl[T] private (
  val capacity:       Int,
  private var _first: StashBufferImpl.Node[T],
  private var _last:  StashBufferImpl.Node[T])
  extends javadsl.StashBuffer[T] with scaladsl.StashBuffer[T] {

  import StashBufferImpl.Node

  private var _size: Int = if (_first eq null) 0 else 1

  override def isEmpty: Boolean = _first eq null

  override def nonEmpty: Boolean = !isEmpty

  override def size: Int = _size

  override def isFull: Boolean = _size == capacity

  override def stash(message: T): StashBufferImpl[T] = {
    if (message == null) throw new NullPointerException
    if (isFull)
      throw new javadsl.StashOverflowException(s"Couldn't add [${message.getClass.getName}] " +
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

  override def foreach(f: T ⇒ Unit): Unit = {
    var node = _first
    while (node ne null) {
      node(f)
      node = node.next
    }
  }

  override def forEach(f: Consumer[T]): Unit = foreach(f.accept(_))

  override def unstashAll(ctx: scaladsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    unstash(ctx, behavior, size, ConstantFun.scalaIdentityFunction[T])

  override def unstashAll(ctx: javadsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    unstashAll(ctx.asScala, behavior)

  override def unstash(ctx: scaladsl.ActorContext[T], behavior: Behavior[T],
                       numberOfMessages: Int, wrap: T ⇒ T): Behavior[T] = {
    val iter = new Iterator[T] {
      override def hasNext: Boolean = StashBufferImpl.this.nonEmpty
      override def next(): T = wrap(StashBufferImpl.this.dropHead())
    }.take(numberOfMessages)
    Behavior.interpretMessages[T](behavior, ctx, iter)
  }

  override def unstash(ctx: javadsl.ActorContext[T], behavior: Behavior[T],
                       numberOfMessages: Int, wrap: JFunction[T, T]): Behavior[T] =
    unstash(ctx.asScala, behavior, numberOfMessages, x ⇒ wrap.apply(x))

  override def toString: String =
    s"StashBuffer($size/$capacity)"
}

