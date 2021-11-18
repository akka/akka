/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.annotation.InternalApi

import scala.collection.{ immutable, mutable }
import scala.collection.AbstractIterator
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object RecencyList {
  def empty[A]: RecencyList[A] = new RecencyList[A](new NanoClock)

  private final class Node[A](val value: A) {
    var lessRecent, moreRecent: Node[A] = this
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
@InternalApi
private[akka] final class RecencyList[A](clock: RecencyList.Clock) {
  import RecencyList.Node

  private val empty = new Node[A](null.asInstanceOf[A])
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

  private def leastRecent: Node[A] = empty.moreRecent
  private def mostRecent: Node[A] = empty.lessRecent

  private val lessRecent: Node[A] => Node[A] = _.lessRecent
  private val moreRecent: Node[A] => Node[A] = _.moreRecent

  def removeLeastRecent(n: Int = 1, skip: Int = 0): immutable.Seq[A] =
    removeWhile(start = leastRecent, next = moreRecent, limit = n, skip = skip)

  def removeMostRecent(n: Int = 1, skip: Int = 0): immutable.Seq[A] =
    removeWhile(start = mostRecent, next = lessRecent, limit = n, skip = skip)

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
    node.moreRecent = empty
    node.lessRecent = mostRecent
    node.moreRecent.lessRecent = node
    node.lessRecent.moreRecent = node
    node.timestamp = clock.currentTime()
  }

  private def removeFromCurrentPosition(node: Node[A]): Unit = {
    node.lessRecent.moreRecent = node.moreRecent
    node.moreRecent.lessRecent = node.lessRecent
  }

  private val continueToLimit: Node[A] => Boolean = _ => true

  private def removeWhile(
      start: Node[A],
      next: Node[A] => Node[A],
      continueWhile: Node[A] => Boolean = continueToLimit,
      limit: Int = size,
      skip: Int = 0): immutable.Seq[A] = {
    var count = 0
    var node = start
    val max = limit + skip
    val values = mutable.ListBuffer.empty[A]
    while ((node ne empty) && continueWhile(node) && (count < max)) {
      count += 1
      if (count > skip) {
        removeFromCurrentPosition(node)
        lookupNode -= node.value
        values += node.value
      }
      node = next(node)
    }
    values.result()
  }

  private def iterator(start: Node[A], shift: Node[A] => Node[A]): Iterator[A] =
    new AbstractIterator[A] {
      private[this] var current = start
      override def hasNext: Boolean = current ne empty
      override def next(): A = {
        val value = current.value
        current = shift(current)
        value
      }
    }
}
