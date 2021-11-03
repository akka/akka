/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import scala.collection.{ immutable, mutable }
import scala.collection.AbstractIterator
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
private[akka] object RecencyList {
  def empty[A]: RecencyList[A] = new RecencyList[A](new NanoClock)

  private class Node[A](val value: A) {
    var less, more: Node[A] = _
    var timestamp: Long = 0L
  }

  trait Clock {
    def currentTime(): Long
    def earlierTime(duration: FiniteDuration): Long
  }

  final class NanoClock extends Clock {
    override def currentTime(): Long = System.nanoTime()
    override def earlierTime(duration: FiniteDuration): Long = currentTime() - duration.toNanos
  }
}

/**
 * INTERNAL API
 *
 * Mutable non-thread-safe recency list.
 * Used for tracking recency of elements for implementing least/most recently used eviction policies.
 * Implemented using a doubly-linked list plus hash map for lookup, so that all operations are constant time.
 */
private[akka] class RecencyList[A](clock: RecencyList.Clock) {
  import RecencyList.Node

  private var leastRecent, mostRecent: Node[A] = _
  private val lookupNode = mutable.Map.empty[A, Node[A]]

  def size: Int = lookupNode.size

  def update(value: A): RecencyList[A] = {
    if (lookupNode.contains(value)) {
      val node = lookupNode(value)
      removeFromCurrentPosition(node)
      addAsMostRecent(node)
    } else {
      val node = new Node(value)
      addAsMostRecent(node)
      lookupNode += value -> node
    }
    this
  }

  def remove(value: A): RecencyList[A] = {
    if (lookupNode.contains(value)) {
      val node = lookupNode(value)
      removeFromCurrentPosition(node)
      lookupNode -= value
    }
    this
  }

  def contains(value: A): Boolean = lookupNode.contains(value)

  private val lessRecent: Node[A] => Node[A] = _.less
  private val moreRecent: Node[A] => Node[A] = _.more

  def removeLeastRecent(n: Int = 1): immutable.Seq[A] =
    removeWhile(start = leastRecent, next = moreRecent, limit = n)

  def removeMostRecent(n: Int = 1): immutable.Seq[A] =
    removeWhile(start = mostRecent, next = lessRecent, limit = n)

  def removeLeastRecentOutside(duration: FiniteDuration): immutable.Seq[A] = {
    val min = clock.earlierTime(duration)
    removeWhile(start = leastRecent, next = moreRecent, continueWhile = _.timestamp < min)
  }

  def removeMostRecentWithin(duration: FiniteDuration): immutable.Seq[A] = {
    val max = clock.earlierTime(duration)
    removeWhile(start = mostRecent, next = lessRecent, continueWhile = _.timestamp > max)
  }

  def leastToMostRecent: Iterator[A] = iterator(start = leastRecent, shift = moreRecent)

  def mostToLeastRecent: Iterator[A] = iterator(start = mostRecent, shift = lessRecent)

  private def addAsMostRecent(node: Node[A]): Unit = {
    node.more = null
    node.less = mostRecent
    if (mostRecent ne null) mostRecent.more = node
    mostRecent = node
    if (leastRecent eq null) leastRecent = mostRecent
    node.timestamp = clock.currentTime()
  }

  private def removeFromCurrentPosition(node: Node[A]): Unit = {
    if (node.less eq null) leastRecent = node.more
    else node.less.more = node.more
    if (node.more eq null) mostRecent = node.less
    else node.more.less = node.less
  }

  private val continueToLimit: Node[A] => Boolean = _ => true

  private def removeWhile(
      start: Node[A],
      next: Node[A] => Node[A],
      continueWhile: Node[A] => Boolean = continueToLimit,
      limit: Int = size): immutable.Seq[A] = {
    var count = 0
    var node = start
    val values = mutable.ListBuffer.empty[A]
    while ((node ne null) && continueWhile(node) && (count < limit)) {
      count += 1
      removeFromCurrentPosition(node)
      lookupNode -= node.value
      values += node.value
      node = next(node)
    }
    values.result()
  }

  private def iterator(start: Node[A], shift: Node[A] => Node[A]): Iterator[A] = new AbstractIterator[A] {
    private[this] var current = start
    override def hasNext: Boolean = current ne null
    override def next(): A = {
      val value = current.value
      current = shift(current)
      value
    }
  }
}
