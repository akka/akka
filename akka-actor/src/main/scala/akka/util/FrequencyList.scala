/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.annotation.InternalApi

import scala.collection.{ immutable, mutable, AbstractIterator }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object FrequencyList {
  def empty[A]: FrequencyList[A] = new FrequencyList[A]

  private final class FrequencyNode[A](val count: Int) {
    var lessFrequent, moreFrequent: OptionVal[FrequencyNode[A]] = OptionVal.None
    var leastRecent, mostRecent: OptionVal[Node[A]] = OptionVal.None
  }

  private final class Node[A](val value: A) {
    var frequencyNode: OptionVal[FrequencyNode[A]] = OptionVal.None
    var lessRecent, moreRecent: OptionVal[Node[A]] = OptionVal.None
  }
}

/**
 * INTERNAL API
 *
 * Mutable non-thread-safe frequency list.
 * Used for tracking frequency of elements for implementing least/most frequently used eviction policies.
 * Implemented using a doubly-linked list of doubly-linked lists, with lookup, so that all operations are constant time.
 */
@InternalApi
private[akka] final class FrequencyList[A] {
  import FrequencyList.{ FrequencyNode, Node }

  private var leastFrequent, mostFrequent: OptionVal[FrequencyNode[A]] = OptionVal.None
  private val lookupNode = mutable.Map.empty[A, Node[A]]

  def size: Int = lookupNode.size

  def update(value: A): FrequencyList[A] = {
    if (lookupNode.contains(value)) {
      val node = lookupNode(value)
      increaseFrequency(node)
    } else {
      val node = new Node(value)
      addAsLeastFrequent(node)
      lookupNode += value -> node
    }
    this
  }

  def remove(value: A): FrequencyList[A] = {
    if (lookupNode.contains(value)) {
      val node = lookupNode(value)
      removeFromCurrentPosition(node)
      lookupNode -= value
    }
    this
  }

  def contains(value: A): Boolean = lookupNode.contains(value)

  private def leastNode: OptionVal[Node[A]] =
    if (leastFrequent.isDefined) leastFrequent.get.leastRecent else OptionVal.None

  private def mostNode: OptionVal[Node[A]] =
    if (mostFrequent.isDefined) mostFrequent.get.mostRecent else OptionVal.None

  private val lessDirection: Node[A] => OptionVal[Node[A]] = { node =>
    if (node.lessRecent.isDefined) node.lessRecent
    else if (node.frequencyNode.isDefined && node.frequencyNode.get.lessFrequent.isDefined)
      node.frequencyNode.get.lessFrequent.get.mostRecent
    else OptionVal.None
  }

  private val moreDirection: Node[A] => OptionVal[Node[A]] = { node =>
    if (node.moreRecent.isDefined) node.moreRecent
    else if (node.frequencyNode.isDefined && node.frequencyNode.get.moreFrequent.isDefined)
      node.frequencyNode.get.moreFrequent.get.leastRecent
    else OptionVal.None
  }

  def removeLeastFrequent(n: Int = 1, skip: OptionVal[A] = OptionVal.none[A]): immutable.Seq[A] =
    removeWhile(start = leastNode, next = moreDirection, limit = n, skip = skip)

  def removeMostFrequent(n: Int = 1, skip: OptionVal[A] = OptionVal.none[A]): immutable.Seq[A] =
    removeWhile(start = mostNode, next = lessDirection, limit = n, skip = skip)

  def leastToMostFrequent: Iterator[A] = iterator(start = leastNode, shift = moreDirection)

  def mostToLeastFrequent: Iterator[A] = iterator(start = mostNode, shift = lessDirection)

  private def hasFrequency(frequencyNode: OptionVal[FrequencyNode[A]], count: Int): Boolean =
    frequencyNode.isDefined && frequencyNode.get.count == count

  private def addAsLeastFrequent(node: Node[A]): Unit = {
    if (hasFrequency(leastFrequent, count = 1)) {
      addToFrequency(node, leastFrequent.get)
    } else {
      val frequencyOne = new FrequencyNode[A](count = 1)
      frequencyOne.moreFrequent = leastFrequent
      if (leastFrequent.isDefined) leastFrequent.get.lessFrequent = OptionVal.Some(frequencyOne)
      leastFrequent = OptionVal.Some(frequencyOne)
      if (mostFrequent.isEmpty) mostFrequent = leastFrequent
      addToFrequency(node, frequencyOne)
    }
  }

  private def increaseFrequency(node: Node[A]): Unit = {
    if (node.frequencyNode.isDefined) {
      val currentFrequencyNode = node.frequencyNode.get
      val nextCount = currentFrequencyNode.count + 1
      val nextFrequencyNode =
        if (hasFrequency(currentFrequencyNode.moreFrequent, nextCount)) {
          currentFrequencyNode.moreFrequent.get
        } else {
          val frequencyNode = new FrequencyNode[A](nextCount)
          frequencyNode.lessFrequent = OptionVal.Some(currentFrequencyNode)
          frequencyNode.moreFrequent = currentFrequencyNode.moreFrequent
          currentFrequencyNode.moreFrequent = OptionVal.Some(frequencyNode)
          if (frequencyNode.moreFrequent.isDefined)
            frequencyNode.moreFrequent.get.lessFrequent = OptionVal.Some(frequencyNode)
          else mostFrequent = OptionVal.Some(frequencyNode)
          frequencyNode
        }
      removeFromCurrentPosition(node)
      addToFrequency(node, nextFrequencyNode)
    }
  }

  private def addToFrequency(node: Node[A], frequencyNode: FrequencyNode[A]): Unit = {
    node.frequencyNode = OptionVal.Some(frequencyNode)
    node.moreRecent = OptionVal.None
    node.lessRecent = frequencyNode.mostRecent
    if (frequencyNode.mostRecent.isDefined) frequencyNode.mostRecent.get.moreRecent = OptionVal.Some(node)
    frequencyNode.mostRecent = OptionVal.Some(node)
    if (frequencyNode.leastRecent.isEmpty) frequencyNode.leastRecent = frequencyNode.mostRecent
  }

  private def removeFromCurrentPosition(node: Node[A]): Unit = {
    if (node.frequencyNode.isDefined) {
      val frequencyNode = node.frequencyNode.get
      if (node.lessRecent.isEmpty) frequencyNode.leastRecent = node.moreRecent
      else node.lessRecent.get.moreRecent = node.moreRecent
      if (node.moreRecent.isEmpty) frequencyNode.mostRecent = node.lessRecent
      else node.moreRecent.get.lessRecent = node.lessRecent
      if (frequencyNode.mostRecent.isEmpty) {
        if (frequencyNode.lessFrequent.isEmpty) leastFrequent = frequencyNode.moreFrequent
        else frequencyNode.lessFrequent.get.moreFrequent = frequencyNode.moreFrequent
        if (frequencyNode.moreFrequent.isEmpty) mostFrequent = frequencyNode.lessFrequent
        else frequencyNode.moreFrequent.get.lessFrequent = frequencyNode.lessFrequent
      }
    }
  }

  private def removeWhile(
      start: OptionVal[Node[A]],
      next: Node[A] => OptionVal[Node[A]],
      limit: Int,
      skip: OptionVal[A]): immutable.Seq[A] = {
    var count = 0
    var node = start
    val values = mutable.ListBuffer.empty[A]
    while (node.isDefined && (count < limit)) {
      if (!skip.contains(node.get.value)) {
        count += 1
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
