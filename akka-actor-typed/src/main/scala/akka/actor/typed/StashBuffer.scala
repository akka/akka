/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import java.util.function.Consumer

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
    internalUnstashAll(ctx.asInstanceOf[ActorContext[T]], behavior)

  /**
   * Java API: Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message.
   */
  def unstashAll(ctx: javadsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    internalUnstashAll(ctx.asInstanceOf[ActorContext[T]], behavior)

  /**
   * Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message.
   */
  private def internalUnstashAll(ctx: ActorContext[T], behavior: Behavior[T]): Behavior[T] = {
    buffer.foldLeft(behavior) { (b, msg) ⇒
      if (!Behavior.isAlive(b)) b
      else {
        // delayed application of signals and messages in the order they arrived
        msg match {
          case s: Signal ⇒
            Behavior.interpretSignal(b, ctx, s)
          case other ⇒
            Behavior.interpretMessage(b, ctx, other)
        }
      }
    }
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
    internalUnstashAll(ctx.asInstanceOf[ActorContext[T]], behavior)

  /**
   * Java API: Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message. The `MutableStashBuffer` will be
   * empty after processing all messages, unless an exception is thrown.
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `MutableStashBuffer`, but unprocessed messages remain.
   */
  def unstashAll(ctx: javadsl.ActorContext[T], behavior: Behavior[T]): Behavior[T] =
    internalUnstashAll(ctx.asInstanceOf[ActorContext[T]], behavior)

  /**
   * Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message. The `MutableStashBuffer` will be
   * empty after processing all messages, unless an exception is thrown.
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `MutableStashBuffer`, but unprocessed messages remain.
   */
  private def internalUnstashAll(ctx: ActorContext[T], behavior: Behavior[T]): Behavior[T] = {
    var b = behavior
    while (nonEmpty) {
      val msg = dropHead()
      if (Behavior.isAlive(b)) {
        msg match {
          case s: Signal ⇒
            Behavior.interpretSignal(b, ctx, s)
          case other ⇒
            b = Behavior.interpretMessage(b, ctx, other)
        }
      }
    }
    b
  }

}

/**
 * Is thrown when the size of the stash exceeds the capacity of the stash buffer.
 */
class StashOverflowException(message: String) extends RuntimeException(message)
