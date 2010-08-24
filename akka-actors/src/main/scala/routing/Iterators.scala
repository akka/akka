/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.routing

import se.scalablesolutions.akka.actor.ActorRef

/**
 * An Iterator that is either always empty or yields an infinite number of Ts.
 */
trait InfiniteIterator[T] extends Iterator[T]

/**
 * CyclicIterator is a round-robin style InfiniteIterator that cycles the supplied List.
 */
class CyclicIterator[T](items: List[T]) extends InfiniteIterator[T] {
  @volatile private[this] var current: List[T] = items

  def hasNext = items != Nil

  def next = {
    val nc = if (current == Nil) items else current
    current = nc.tail
    nc.head
  }

  override def exists(f: T => Boolean): Boolean = items.exists(f)

}

/**
 * This InfiniteIterator always returns the Actor that has the currently smallest mailbox
 * useful for work-stealing.
 */
class SmallestMailboxFirstIterator(items : List[ActorRef]) extends InfiniteIterator[ActorRef] {
  def hasNext = items != Nil

  def next = items.reduceLeft((a1, a2) => if (a1.mailboxSize < a2.mailboxSize) a1 else a2)

  override def exists(f: ActorRef => Boolean): Boolean = items.exists(f)
}
