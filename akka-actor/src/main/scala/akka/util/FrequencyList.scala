/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.annotation.InternalApi

import scala.collection.{ immutable, mutable }
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object FrequencyList {
  def empty[A]: FrequencyList[A] = new FrequencyList[A](OptionVal.None)

  object withOverallRecency {
    def empty[A]: FrequencyList[A] = new FrequencyList[A](OptionVal.Some(new RecencyList.NanoClock))
  }

  private final class FrequencyNode[A](val count: Int) {
    var lessFrequent, moreFrequent: OptionVal[FrequencyNode[A]] = OptionVal.None
    val nodes = new DoubleLinkedList[Node[A]](
      getPrevious = _.lessRecent,
      getNext = _.moreRecent,
      setPrevious = _.lessRecent = _,
      setNext = _.moreRecent = _)
  }

  private final class Node[A](val value: A, initialFrequency: FrequencyNode[A]) {
    var frequency: FrequencyNode[A] = initialFrequency
    var lessRecent, moreRecent: OptionVal[Node[A]] = OptionVal.None
    var overallLessRecent, overallMoreRecent: OptionVal[Node[A]] = OptionVal.None
    var timestamp: Long = 0L
  }
}

/**
 * INTERNAL API
 *
 * Mutable non-thread-safe frequency list.
 * Used for tracking frequency of elements for implementing least/most frequently used eviction policies.
 * Implemented using a doubly-linked list of doubly-linked lists, with lookup, so that all operations are constant time.
 * Elements with the same frequency are stored in order of update (recency within the same frequency count).
 * Overall recency can also be enabled, to support time-based eviction policies, without using a secondary recency list.
 */
@InternalApi
private[akka] final class FrequencyList[A](clock: OptionVal[RecencyList.Clock]) {
  import FrequencyList.{ FrequencyNode, Node }

  private val frequency = new DoubleLinkedList[FrequencyNode[A]](
    getPrevious = _.lessFrequent,
    getNext = _.moreFrequent,
    setPrevious = _.lessFrequent = _,
    setNext = _.moreFrequent = _)

  private val overallRecency = new DoubleLinkedList[Node[A]](
    getPrevious = _.overallLessRecent,
    getNext = _.overallMoreRecent,
    setPrevious = _.overallLessRecent = _,
    setNext = _.overallMoreRecent = _)

  private val lookupNode = mutable.Map.empty[A, Node[A]]

  def size: Int = lookupNode.size

  def update(value: A): FrequencyList[A] = {
    if (lookupNode.contains(value)) {
      val node = lookupNode(value)
      increaseFrequency(node)
      if (clock.isDefined) {
        node.timestamp = clock.get.currentTime()
        overallRecency.moveToBack(node)
      }
    } else {
      val node = addAsLeastFrequent(value)
      lookupNode += value -> node
      if (clock.isDefined) {
        node.timestamp = clock.get.currentTime()
        overallRecency.append(node)
      }
    }
    this
  }

  def remove(value: A): FrequencyList[A] = {
    if (lookupNode.contains(value)) {
      removeNode(lookupNode(value))
    }
    this
  }

  def contains(value: A): Boolean = lookupNode.contains(value)

  def leastToMostFrequent: Iterator[A] = forwardIterator.map(_.value)

  def mostToLeastFrequent: Iterator[A] = backwardIterator.map(_.value)

  def removeLeastFrequent(n: Int): immutable.Seq[A] =
    if (n == 1) removeLeastFrequent() // optimised removal of just 1 node
    else forwardIterator.take(n).map(removeNode).toList

  def removeLeastFrequent(n: Int = 1, skip: OptionVal[A]): immutable.Seq[A] =
    forwardIterator.filterNot(node => skip.contains(node.value)).take(n).map(removeNode).toList

  def removeLeastFrequent(): immutable.Seq[A] = frequency.getFirst match {
    case OptionVal.Some(least) =>
      least.nodes.getFirst match {
        case OptionVal.Some(first) => List(removeNode(first))
        case _                     => Nil
      }
    case _ => Nil
  }

  def removeMostFrequent(n: Int): immutable.Seq[A] =
    if (n == 1) removeMostFrequent() // optimised removal of just 1 node
    else backwardIterator.take(n).map(removeNode).toList

  def removeMostFrequent(n: Int = 1, skip: OptionVal[A]): immutable.Seq[A] =
    backwardIterator.filterNot(node => skip.contains(node.value)).take(n).map(removeNode).toList

  def removeMostFrequent(): immutable.Seq[A] = frequency.getLast match {
    case OptionVal.Some(most) =>
      most.nodes.getLast match {
        case OptionVal.Some(last) => List(removeNode(last))
        case _                    => Nil
      }
    case _ => Nil
  }

  def overallLeastToMostRecent: Iterator[A] = overallRecency.forwardIterator.map(_.value)

  def overallMostToLeastRecent: Iterator[A] = overallRecency.backwardIterator.map(_.value)

  def removeOverallLeastRecent(n: Int = 1): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    overallRecency.forwardIterator.take(n).map(removeNode).toList
  }

  def removeOverallMostRecent(n: Int = 1): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    overallRecency.backwardIterator.take(n).map(removeNode).toList
  }

  def removeOverallLeastRecentOutside(duration: FiniteDuration): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    val min = clock.get.earlierTime(duration)
    overallRecency.forwardIterator.takeWhile(_.timestamp < min).map(removeNode).toList
  }

  def removeOverallMostRecentWithin(duration: FiniteDuration): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    val max = clock.get.earlierTime(duration)
    overallRecency.backwardIterator.takeWhile(_.timestamp > max).map(removeNode).toList
  }

  private def addAsLeastFrequent(value: A): Node[A] = {
    val one = frequency.getFirstOrElsePrepend(_.count == 1, new FrequencyNode[A](count = 1))
    val node = new Node(value, one)
    addToFrequency(node, one)
    node
  }

  private def increaseFrequency(node: Node[A]): Unit = {
    val nextCount = node.frequency.count + 1
    val next = frequency.getNextOrElseInsert(node.frequency, _.count == nextCount, new FrequencyNode[A](nextCount))
    removeFromFrequency(node)
    addToFrequency(node, next)
  }

  private def addToFrequency(node: Node[A], frequencyNode: FrequencyNode[A]): Unit = {
    node.frequency = frequencyNode
    frequencyNode.nodes.append(node)
  }

  private def removeFromFrequency(node: Node[A]): Unit = {
    val frequencyNode = node.frequency
    frequencyNode.nodes.remove(node)
    if (frequencyNode.nodes.isEmpty) frequency.remove(frequencyNode)
  }

  private def removeNode(node: Node[A]): A = {
    val value = node.value
    removeFromFrequency(node)
    if (clock.isDefined) overallRecency.remove(node)
    lookupNode -= value
    value
  }

  private def forwardIterator: Iterator[Node[A]] = frequency.forwardIterator.flatMap(_.nodes.forwardIterator)

  private def backwardIterator: Iterator[Node[A]] = frequency.backwardIterator.flatMap(_.nodes.backwardIterator)
}
