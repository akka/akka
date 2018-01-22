/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import java.util.function.Consumer
import java.util.function.{ Function ⇒ JFunction }

import scala.annotation.tailrec

import akka.actor.typed.scaladsl
import akka.actor.typed.javadsl
import akka.annotation.InternalApi
import akka.actor.typed.Behavior
import akka.actor.typed.ActorContext
import akka.actor.typed.Signal
import akka.util.ConstantFun

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ImmutableStashBufferImpl {
  def apply[T](capacity: Int): ImmutableStashBufferImpl[T] =
    new ImmutableStashBufferImpl(capacity, Vector.empty)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ImmutableStashBufferImpl[T](val capacity: Int, buffer: Vector[T])
  extends javadsl.ImmutableStashBuffer[T] with scaladsl.ImmutableStashBuffer[T] {

  override def isEmpty: Boolean = buffer.isEmpty

  override def nonEmpty: Boolean = !isEmpty

  override def size: Int = buffer.size

  override def isFull: Boolean = size == capacity

  override def stash(message: T): ImmutableStashBufferImpl[T] = {
    if (message == null) throw new NullPointerException
    if (isFull)
      throw new javadsl.StashOverflowException(s"Couldn't add [${message.getClass.getName}] " +
        s"because stash with capacity [$capacity] is full")

    new ImmutableStashBufferImpl(capacity, buffer :+ message)
  }

  override def dropHead(): ImmutableStashBufferImpl[T] =
    if (buffer.nonEmpty) new ImmutableStashBufferImpl(capacity, buffer.tail)
    else throw new NoSuchElementException("head of empty buffer")

  override def drop(numberOfMessages: Int): ImmutableStashBufferImpl[T] =
    if (isEmpty) this
    else new ImmutableStashBufferImpl(capacity, buffer.drop(numberOfMessages))

  override def head: T =
    if (buffer.nonEmpty) buffer.head
    else throw new NoSuchElementException("head of empty buffer")

  override def foreach(f: T ⇒ Unit): Unit =
    buffer.foreach(f)

  override def forEach(f: Consumer[T]): Unit = foreach(f.accept)

  override def unstashAll(ctx: scaladsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    unstash(ctx, behavior, size, ConstantFun.scalaIdentityFunction[T])

  override def unstashAll(ctx: javadsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    unstashAll(ctx.asScala, behavior)

  override def unstash(scaladslCtx: scaladsl.ActorContext[T], behavior: Behavior[T],
                       numberOfMessages: Int, wrap: T ⇒ T): Behavior[T] = {
    val ctx = scaladslCtx.asInstanceOf[ActorContext[T]]
    val iter = buffer.iterator.take(numberOfMessages).map(wrap)
    Behavior.interpretMessages[T](behavior, ctx, iter)
  }

  override def unstash(ctx: javadsl.ActorContext[T], behavior: Behavior[T],
                       numberOfMessages: Int, wrap: JFunction[T, T]): Behavior[T] =
    unstash(ctx.asScala, behavior, numberOfMessages, x ⇒ wrap.apply(x))

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object MutableStashBufferImpl {
  private final class Node[T](var next: Node[T], val message: T) {
    def apply(f: T ⇒ Unit): Unit = f(message)
  }

  def apply[T](capacity: Int): MutableStashBufferImpl[T] =
    new MutableStashBufferImpl(capacity, null, null)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class MutableStashBufferImpl[T] private (
  val capacity:      Int,
  private var _head: MutableStashBufferImpl.Node[T],
  private var _tail: MutableStashBufferImpl.Node[T])
  extends javadsl.MutableStashBuffer[T] with scaladsl.MutableStashBuffer[T] {

  import MutableStashBufferImpl.Node

  private var _size: Int = if (_head eq null) 0 else 1

  override def isEmpty: Boolean = _head eq null

  override def nonEmpty: Boolean = !isEmpty

  override def size: Int = _size

  override def isFull: Boolean = _size == capacity

  override def stash(message: T): MutableStashBufferImpl[T] = {
    if (message == null) throw new NullPointerException
    if (isFull)
      throw new javadsl.StashOverflowException(s"Couldn't add [${message.getClass.getName}] " +
        s"because stash with capacity [$capacity] is full")

    val node = new Node(null, message)
    if (isEmpty) {
      _head = node
      _tail = node
    } else {
      _tail.next = node
      _tail = node
    }
    _size += 1
    this
  }

  override def dropHead(): T = {
    val message = head
    _head = _head.next
    _size -= 1
    if (isEmpty)
      _tail = null

    message
  }

  override def head: T =
    if (nonEmpty) _head.message
    else throw new NoSuchElementException("head of empty buffer")

  override def foreach(f: T ⇒ Unit): Unit = {
    var node = _head
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

  override def unstash(scaladslCtx: scaladsl.ActorContext[T], behavior: Behavior[T],
                       numberOfMessages: Int, wrap: T ⇒ T): Behavior[T] = {
    val iter = new Iterator[T] {
      override def hasNext: Boolean = MutableStashBufferImpl.this.nonEmpty
      override def next(): T = MutableStashBufferImpl.this.dropHead()
    }.take(numberOfMessages).map(wrap)
    val ctx = scaladslCtx.asInstanceOf[ActorContext[T]]
    Behavior.interpretMessages[T](behavior, ctx, iter)
  }

  override def unstash(ctx: javadsl.ActorContext[T], behavior: Behavior[T],
                       numberOfMessages: Int, wrap: JFunction[T, T]): Behavior[T] =
    unstash(ctx.asScala, behavior, numberOfMessages, x ⇒ wrap.apply(x))

}

