/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.ActorID

trait InfiniteIterator[T] extends Iterator[T]

class CyclicIterator[T](items: List[T]) extends InfiniteIterator[T] {
  @volatile private[this] var current: List[T] = items

  def hasNext = items != Nil

  def next = {
    val nc = if (current == Nil) items else current
    current = nc.tail
    nc.head
  }
}

class SmallestMailboxFirstIterator(items : List[ActorID]) extends InfiniteIterator[ActorID] {
  def hasNext = items != Nil

  def next = items.reduceLeft((a1, a2) => if (a1.mailboxSize < a2.mailboxSize) a1 else a2)
} 