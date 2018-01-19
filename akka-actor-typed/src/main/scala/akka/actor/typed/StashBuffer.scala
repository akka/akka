/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import java.util.function.Consumer
import java.util.function.{ Function ⇒ JFunction }

import scala.annotation.tailrec

// FIXME break these up in scaladsl/javadsl interfaces and common impl

object ImmutableStashBuffer {
  /**
   * Create an empty message buffer.
   *
   * @param capacity the buffer can hold at most this number of messages
   * @return an empty message buffer
   */
  def empty[T](capacity: Int): ImmutableStashBuffer[T] = new ImmutableStashBuffer[T](capacity, Vector.empty)

}

/**
 * A thread safe immutable message buffer that can be used to buffer messages inside actors.
 *
 * The buffer can hold at most the given `capacity` number of messages.
 */
final class ImmutableStashBuffer[T](val capacity: Int, buffer: Vector[T]) {

  /**
   * Check if the message buffer is empty.
   *
   * @return if the buffer is empty
   */
  def isEmpty: Boolean = buffer.isEmpty

  /**
   * Check if the message buffer is not empty.
   *
   * @return if the buffer is not empty
   */
  def nonEmpty: Boolean = !isEmpty

  /**
   * How many elements are in the message buffer.
   *
   * @return the number of elements in the message buffer
   */
  def size: Int = buffer.size

  /**
   * @return `true` if no more messages can be added, i.e. size equals the capacity of the stash buffer
   */
  def isFull: Boolean = size == capacity

  /**
   * Add one element to the end of the message buffer. Note that this class is
   * immutable so the returned instance contains the added message.
   *
   * [[StashOverflowException]] is thrown if the buffer [[MutableStashBuffer#isFull]].
   *
   * @param message the message to buffer
   * @return this message buffer
   */
  def append(message: T): ImmutableStashBuffer[T] = {
    if (message == null) throw new NullPointerException
    if (isFull)
      throw new StashOverflowException(s"Couldn't add [${message.getClass.getName}] " +
        s"because stash with capacity [$capacity] is full")

    new ImmutableStashBuffer(capacity, buffer :+ message)
  }

  /**
   * Add one element to the end of the message buffer. Note that this class is
   * immutable so the returned instance contains the added message.
   *
   * [[StashOverflowException]] is thrown if the buffer [[MutableStashBuffer#isFull]].
   *
   * @param message the message to buffer
   * @return this message buffer
   */
  def :+(message: T): ImmutableStashBuffer[T] =
    append(message)

  /**
   * Remove the first element of the message buffer. Note that this class is
   * immutable so the head element is removed in the returned instance.
   *
   * @throws `NoSuchElementException` if the buffer is empty
   */
  def dropHead(): ImmutableStashBuffer[T] =
    if (buffer.nonEmpty) new ImmutableStashBuffer(capacity, buffer.tail)
    else throw new NoSuchElementException("head of empty buffer")

  /**
   * Remove the first `numberOfMessages` of the message buffer. Note that this class is
   * immutable so the elements are removed in the returned instance.
   */
  def drop(numberOfMessages: Int): ImmutableStashBuffer[T] =
    if (isEmpty) this
    else new ImmutableStashBuffer(capacity, buffer.drop(numberOfMessages))

  /**
   * Return the first element of the message buffer.
   *
   * @return the first element or throws `NoSuchElementException` if the buffer is empty
   * @throws `NoSuchElementException` if the buffer is empty
   */
  def head: T =
    if (buffer.nonEmpty) buffer.head
    else throw new NoSuchElementException("head of empty buffer")

  /**
   * Iterate over all elements of the buffer and apply a function to each element.
   *
   * @param f the function to apply to each element
   */
  def foreach(f: T ⇒ Unit): Unit =
    buffer.foreach(f)

  /**
   * Java API
   *
   * Iterate over all elements of the buffer and apply a function to each element.
   *
   * @param f the function to apply to each element
   */
  def forEach(f: Consumer[T]): Unit = foreach(f.accept)

  /**
   * Scala API: Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message.
   */
  def unstashAll(ctx: scaladsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, size, identity)

  /**
   * Java API: Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message.
   */
  def unstashAll(ctx: javadsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, size, identity)

  /**
   * Scala API: Process `numberOfMessages` of the stashed messages with the `behavior`
   * and the returned [[Behavior]] from each processed message.
   */
  def unstash(ctx: scaladsl.ActorContext[T], behavior: Behavior[T], numberOfMessages: Int, wrap: T ⇒ T): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, numberOfMessages, wrap)

  /**
   * Java API: Process `numberOfMessages` of the stashed messages with the `behavior`
   * and the returned [[Behavior]] from each processed message.
   */
  def unstash(ctx: javadsl.ActorContext[T], behavior: Behavior[T], numberOfMessages: Int, wrap: JFunction[T, T]): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, numberOfMessages, wrap.apply)

  private def internalUnstash(ctx: ActorContext[T], behavior: Behavior[T], numberOfMessages: Int, wrap: T ⇒ T): Behavior[T] = {

    @tailrec def unstashOne(iter: Iterator[T], count: Int, b: Behavior[T]): Behavior[T] = {
      val b2 = Behavior.undefer(b, ctx)
      if (!Behavior.isAlive(b2) || count == numberOfMessages || !iter.hasNext) b2
      else {
        val nextB = iter.next() match {
          case sig: Signal ⇒
            // FIXME wrapping of signals, but how can signals be stashed in the first place?
            Behavior.interpretSignal(b2, ctx, sig)
          case msg ⇒
            Behavior.interpretMessage(b2, ctx, wrap(msg))
        }
        unstashOne(iter, count + 1, Behavior.canonicalize(nextB, b, ctx)) // recursive
      }
    }

    // FIXME I'm not sure I got these undefer and canonicalize right

    unstashOne(buffer.iterator, count = 0, Behavior.undefer(behavior, ctx))
  }

}

object MutableStashBuffer {
  private final class Node[T](var next: Node[T], val message: T) {
    def apply(f: T ⇒ Unit): Unit = f(message)
  }

  /**
   * Create an empty message buffer.
   *
   * @param capacity the buffer can hold at most this number of messages
   * @return an empty message buffer
   */
  def empty[T](capacity: Int): MutableStashBuffer[T] = new MutableStashBuffer[T](capacity, null, null)
}

/**
 * A non thread safe mutable message buffer that can be used to buffer messages inside actors.
 *
 * The buffer can hold at most the given `capacity` number of messages.
 */
final class MutableStashBuffer[T] private (
  val capacity:      Int,
  private var _head: MutableStashBuffer.Node[T],
  private var _tail: MutableStashBuffer.Node[T]) {
  import MutableStashBuffer._

  private var _size: Int = if (_head eq null) 0 else 1

  /**
   * Check if the message buffer is empty.
   *
   * @return if the buffer is empty
   */
  def isEmpty: Boolean = _head eq null

  /**
   * Check if the message buffer is not empty.
   *
   * @return if the buffer is not empty
   */
  def nonEmpty: Boolean = !isEmpty

  /**
   * How many elements are in the message buffer.
   *
   * @return the number of elements in the message buffer
   */
  def size: Int = _size

  /**
   * @return `true` if no more messages can be added, i.e. size equals the capacity of the stash buffer
   */
  def isFull: Boolean = _size == capacity

  /**
   * Add one element to the end of the message buffer.
   *
   * [[StashOverflowException]] is thrown if the buffer [[MutableStashBuffer#isFull]].
   *
   * @param message the message to buffer
   * @return this message buffer
   */
  def append(message: T): MutableStashBuffer[T] = {
    if (message == null) throw new NullPointerException
    if (isFull)
      throw new StashOverflowException(s"Couldn't add [${message.getClass.getName}] " +
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

  /**
   * Return the first element of the message buffer and removes it.
   *
   * @return the first element or throws `NoSuchElementException` if the buffer is empty
   * @throws `NoSuchElementException` if the buffer is empty
   */
  def dropHead(): T = {
    val message = head
    _head = _head.next
    _size -= 1
    if (isEmpty)
      _tail = null

    message
  }

  /**
   * Return the first element of the message buffer without removing it.
   *
   * @return the first element or throws `NoSuchElementException` if the buffer is empty
   * @throws `NoSuchElementException` if the buffer is empty
   */
  def head: T =
    if (nonEmpty) _head.message
    else throw new NoSuchElementException("head of empty buffer")

  /**
   * Iterate over all elements of the buffer and apply a function to each element.
   *
   * @param f the function to apply to each element
   */
  def foreach(f: T ⇒ Unit): Unit = {
    var node = _head
    while (node ne null) {
      node(f)
      node = node.next
    }
  }

  /**
   * Java API
   *
   * Iterate over all elements of the buffer and apply a function to each element.
   *
   * @param f the function to apply to each element
   */
  def forEach(f: Consumer[T]): Unit = foreach(f.accept)

  /**
   * Scala API: Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message. The `MutableStashBuffer` will be
   * empty after processing all messages, unless an exception is thrown.
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `MutableStashBuffer`, but unprocessed messages remain.
   */
  def unstashAll(ctx: scaladsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, size)

  /**
   * Java API: Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message. The `MutableStashBuffer` will be
   * empty after processing all messages, unless an exception is thrown.
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `MutableStashBuffer`, but unprocessed messages remain.
   */
  def unstashAll(ctx: javadsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, size)

  /**
   * Scala API: Process `numberOfMessages` of the stashed messages with the `behavior`
   * and the returned [[Behavior]] from each processed message.
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `MutableStashBuffer`, but unprocessed messages remain.
   */
  def unstash(ctx: scaladsl.ActorContext[T], behavior: Behavior[T], numberOfMessages: Int): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, numberOfMessages)

  /**
   * Java API: Process `numberOfMessages` of the stashed messages with the `behavior`
   * and the returned [[Behavior]] from each processed message.
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `MutableStashBuffer`, but unprocessed messages remain.
   */
  def unstash(ctx: javadsl.ActorContext[T], behavior: Behavior[T], numberOfMessages: Int): Behavior[T] =
    internalUnstash(ctx.asInstanceOf[ActorContext[T]], behavior, numberOfMessages)

  private def internalUnstash(ctx: ActorContext[T], behavior: Behavior[T], numberOfMessages: Int): Behavior[T] = {
    // FIXME DRY with the one in ImmutableStashBuffer, should probably be a method in Behavior instead

    @tailrec def unstashOne(count: Int, b: Behavior[T]): Behavior[T] = {
      if (!Behavior.isAlive(b) || count == numberOfMessages || isEmpty) b
      else {
        val nextB = dropHead() match {
          case sig: Signal ⇒
            Behavior.interpretSignal(b, ctx, sig)
          case msg ⇒
            Behavior.interpretMessage(b, ctx, msg)
        }
        unstashOne(count + 1, Behavior.canonicalize(nextB, b, ctx)) // recursive
      }
    }

    unstashOne(count = 0, Behavior.undefer(behavior, ctx))
  }
}

/**
 * Is thrown when the size of the stash exceeds the capacity of the stash buffer.
 */
class StashOverflowException(message: String) extends RuntimeException(message)
