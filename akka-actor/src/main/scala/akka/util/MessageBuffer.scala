/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.actor.ActorRef
import akka.japi.function.Procedure2

/**
 * A non thread safe mutable message buffer that can be used to buffer messages inside actors.
 */
final class MessageBuffer private (
  private var _head: MessageBuffer.Node,
  private var _tail: MessageBuffer.Node) {
  import MessageBuffer._

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
   * Add one element to the end of the message buffer.
   *
   * @param message the message to buffer
   * @param ref the actor to buffer
   * @return this message buffer
   */
  def append(message: Any, ref: ActorRef): MessageBuffer = {
    val node: Node = new Node(null, message, ref)
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
   * Remove the first element of the message buffer.
   */
  def dropHead(): Unit = if (nonEmpty) {
    _head = _head.next
    _size -= 1
    if (isEmpty)
      _tail = null
  }

  /**
   * Return the first element of the message buffer.
   *
   * @return the first element or an element containing null if the buffer is empty
   */
  def head(): (Any, ActorRef) =
    if (nonEmpty) (_head.message, _head.ref)
    else (null, null)

  /**
   * Java API
   *
   * Return the first element of the message buffer.
   *
   * @return the first element or an element containing null if the buffer is empty
   */
  def getHead(): akka.japi.Pair[Any, ActorRef] = {
    import akka.japi.Pair
    if (nonEmpty) Pair.create(_head.message, _head.ref)
    else Pair.create(null, null)
  }

  /**
   * Iterate over all elements of the buffer and apply a function to each element.
   *
   * @param f the function to apply to each element
   */
  def foreach(f: (Any, ActorRef) ⇒ Unit): Unit = {
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
  def forEach(f: Procedure2[Any, ActorRef]): Unit = foreach { case (message, ref) ⇒ f(message, ref) }
}

object MessageBuffer {
  private final class Node(var next: Node, val message: Any, val ref: ActorRef) {
    def apply(f: (Any, ActorRef) ⇒ Unit): Unit = {
      f(message, ref)
    }
  }

  /**
   * Create an empty message buffer.
   *
   * @return an empty message buffer
   */
  def empty: MessageBuffer = new MessageBuffer(null, null)
}

/**
 * A non thread safe mutable message buffer map that can be used to buffer messages inside actors.
 *
 * @tparam I (Id type)
 */
final class MessageBufferMap[I] {
  import java.{ util ⇒ jutil }

  private val bufferMap = new jutil.HashMap[I, MessageBuffer]

  /**
   * Check if the buffer map is empty.
   *
   * @return if the buffer map is empty
   */
  def isEmpty: Boolean = bufferMap.isEmpty

  /**
   * Check if the buffer map is not empty.
   *
   * @return if the buffer map is not empty
   */
  def nonEmpty: Boolean = !isEmpty

  /**
   * How many ids are in the buffer map.
   *
   * @return the number of ids in the buffer map
   */
  def size: Int = bufferMap.size

  /**
   * How many elements are in the buffers in the buffer map.
   *
   * @return the number of elements in the buffers in the buffer map
   */
  def totalSize: Int = {
    var s: Int = 0
    val values = bufferMap.values().iterator()
    while (values.hasNext) {
      s += values.next().size
    }
    s
  }

  private def getOrAddBuffer(id: I): MessageBuffer = {
    val buffer = bufferMap.get(id)
    if (buffer eq null) {
      val newBuffer = MessageBuffer.empty
      bufferMap.put(id, newBuffer)
      newBuffer
    } else buffer
  }

  /**
   * Add an id to the buffer map
   */
  def add(id: I): Unit = {
    getOrAddBuffer(id)
  }

  /**
   * Append an element to the buffer for an id.
   *
   * @param id the id to add the element to
   * @param message the message to buffer
   * @param ref the actor to buffer
   */
  def append(id: I, message: Any, ref: ActorRef): Unit = {
    val buffer = getOrAddBuffer(id)
    buffer.append(message, ref)
  }

  /**
   * Remove the buffer for an id.
   *
   * @param id the id to remove the buffer for
   */
  def remove(id: I): Unit = {
    bufferMap.remove(id)
  }

  /**
   * Check if the buffer map contains an id.
   *
   * @param id the id to check for
   * @return if the buffer contains the given id
   */
  def contains(id: I): Boolean = {
    bufferMap.containsKey(id)
  }

  /**
   * Get the message buffer for an id, or an empty buffer if the id doesn't exist in the map.
   *
   * @param id the id to get the message buffer for
   * @return the message buffer for the given id or an empty buffer if the id doesn't exist
   */
  def getOrEmpty(id: I): MessageBuffer = {
    val buffer = bufferMap.get(id)
    if (buffer ne null) buffer else MessageBuffer.empty
  }

  /**
   * Iterate over all elements of the buffer map and apply a function to each element.
   *
   * @param f the function to apply to each element
   */
  def foreach(f: (I, MessageBuffer) ⇒ Unit): Unit = {
    val entries = bufferMap.entrySet().iterator()
    while (entries.hasNext) {
      val entry = entries.next()
      f(entry.getKey, entry.getValue)
    }
  }

  /**
   * Java API
   *
   * Iterate over all elements of the buffer map and apply a function to each element.
   *
   * @param f the function to apply to each element
   */
  def forEach(f: Procedure2[I, MessageBuffer]): Unit = foreach { case (id, buffer) ⇒ f(id, buffer) }
}
