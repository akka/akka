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
    var lessRecent, moreRecent: OptionVal[Node[A]] = OptionVal.None
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

  private var leastRecent, mostRecent: OptionVal[Node[A]] = OptionVal.None
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

  private val lessRecent: Node[A] => OptionVal[Node[A]] = _.lessRecent
  private val moreRecent: Node[A] => OptionVal[Node[A]] = _.moreRecent

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
    node.moreRecent = OptionVal.None
    node.lessRecent = mostRecent
    if (mostRecent.isDefined) mostRecent.get.moreRecent = OptionVal.Some(node)
    mostRecent = OptionVal.Some(node)
    if (leastRecent.isEmpty) leastRecent = mostRecent
    node.timestamp = clock.currentTime()
  }

  private def removeFromCurrentPosition(node: Node[A]): Unit = {
    if (node.lessRecent.isEmpty) leastRecent = node.moreRecent
    else node.lessRecent.get.moreRecent = node.moreRecent
    if (node.moreRecent.isEmpty) mostRecent = node.lessRecent
    else node.moreRecent.get.lessRecent = node.lessRecent
  }

  private val continueToLimit: Node[A] => Boolean = _ => true

  private def removeWhile(
      start: OptionVal[Node[A]],
      next: Node[A] => OptionVal[Node[A]],
      continueWhile: Node[A] => Boolean = continueToLimit,
      limit: Int = size,
      skip: Int = 0): immutable.Seq[A] = {
    var count = 0
    var node = start
    val max = limit + skip
    val values = mutable.ListBuffer.empty[A]
    while (node.isDefined && continueWhile(node.get) && (count < max)) {
      count += 1
      if (count > skip) {
        removeFromCurrentPosition(node.get)
        lookupNode -= node.get.value
        values += node.get.value
      }
      node = next(node.get)
    }
    values.result()
  }

  private def iterator(start: OptionVal[Node[A]], shift: Node[A] => OptionVal[Node[A]]): Iterator[A] =
    new AbstractIterator[A] {
      private[this] var current = start
      override def hasNext: Boolean = current.isDefined
      override def next(): A = {
        val value = current.get.value
        current = shift(current.get)
        value
      }
    }
}
