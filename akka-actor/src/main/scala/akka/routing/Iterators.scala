/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.routing

import akka.actor.ActorRef
import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import java.util.concurrent.atomic.AtomicReference
import annotation.tailrec

/**
 * An Iterator that is either always empty or yields an infinite number of Ts.
 */
trait InfiniteIterator[T] extends Iterator[T] {
  val items: Seq[T]
}

/**
 * CyclicIterator is a round-robin style InfiniteIterator that cycles the supplied List.
 */
case class CyclicIterator[T](val items: Seq[T]) extends InfiniteIterator[T] {
  def this(items: java.util.List[T]) = this(items.toList)

  private[this] val current: AtomicReference[Seq[T]] = new AtomicReference(items)

  def hasNext = items != Nil

  def next: T = {
    @tailrec
    def findNext: T = {
      val currentItems = current.get
      val newItems = currentItems match {
        case Nil ⇒ items
        case xs  ⇒ xs
      }

      if (current.compareAndSet(currentItems, newItems.tail)) newItems.head
      else findNext
    }

    findNext
  }

  override def exists(f: T ⇒ Boolean): Boolean = items exists f
}

/**
 * This InfiniteIterator always returns the Actor that has the currently smallest mailbox
 * useful for work-stealing.
 */
case class SmallestMailboxFirstIterator(val items: Seq[ActorRef]) extends InfiniteIterator[ActorRef] {
  def this(items: java.util.List[ActorRef]) = this(items.toList)
  def hasNext = items != Nil

  def next = items.reduceLeft((a1, a2) ⇒ if (a1.dispatcher.mailboxSize(a1) < a2.dispatcher.mailboxSize(a2)) a1 else a2)

  override def exists(f: ActorRef ⇒ Boolean): Boolean = items.exists(f)
}
