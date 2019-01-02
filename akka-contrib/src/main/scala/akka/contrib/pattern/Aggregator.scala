/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.pattern

import akka.actor.Actor
import scala.annotation.tailrec

/**
 * The aggregator is to be mixed into an actor for the aggregator behavior.
 */
@deprecated("Feel free to copy", "2.5.0")
trait Aggregator {
  this: Actor ⇒

  private var processing = false
  private val expectList = WorkList.empty[Actor.Receive]
  private val addBuffer = WorkList.empty[Actor.Receive]

  /**
   * Adds the partial function to the receive set, to be removed on first match.
   * @param fn The receive function.
   * @return The same receive function.
   */
  def expectOnce(fn: Actor.Receive): Actor.Receive = {
    if (processing) addBuffer.add(fn, permanent = false)
    else expectList.add(fn, permanent = false)
    fn
  }

  /**
   * Adds the partial function to the receive set and keeping it in the receive set till removed.
   * @param fn The receive function.
   * @return The same receive function.
   */
  def expect(fn: Actor.Receive): Actor.Receive = {
    if (processing) addBuffer.add(fn, permanent = true)
    else expectList.add(fn, permanent = true)
    fn
  }

  /**
   * Removes the partial function from the receive set.
   * @param fn The receive function.
   * @return True if the partial function is removed, false if not found.
   */
  def unexpect(fn: Actor.Receive): Boolean = {
    if (expectList remove fn) true
    else if (processing && (addBuffer remove fn)) true
    else false
  }

  /**
   * Receive function for handling the aggregations.
   */
  def receive: Actor.Receive = {
    case msg if handleMessage(msg) ⇒ // already dealt with in handleMessage
  }

  /**
   * Handles messages and matches against the expect list.
   * @param msg The message to be handled.
   * @return true if message is successfully processed, false otherwise.
   */
  def handleMessage(msg: Any): Boolean = {
    processing = true
    try {
      expectList process { fn ⇒
        var processed = true
        fn.applyOrElse(msg, (_: Any) ⇒ processed = false)
        processed
      }
    } finally {
      processing = false
      expectList addAll addBuffer
      addBuffer.removeAll()
    }
  }
}

/**
 * Provides the utility methods and constructors to the WorkList class.
 */
@deprecated("Feel free to copy", "2.5.0")
object WorkList {

  def empty[T] = new WorkList[T]

  /**
   * Singly linked list entry implementation for WorkList.
   * @param ref The item reference, None for head entry
   * @param permanent If the entry is to be kept after processing
   */
  class Entry[T](val ref: Option[T], val permanent: Boolean) {
    var next: Entry[T] = null
    var isDeleted = false
  }
}

/**
 * Fast, small, and dirty implementation of a linked list that removes transient work entries once they are processed.
 * The list is not thread safe! However it is expected to be reentrant. This means a processing function can add/remove
 * entries from the list while processing. Most important, a processing function can remove its own entry from the list.
 * The first remove must return true and any subsequent removes must return false.
 */
@deprecated("Feel free to copy", "2.5.0")
class WorkList[T] {

  import WorkList._

  val head = new Entry[T](None, true)
  var tail = head

  /**
   * Appends an entry to the work list.
   * @param ref The entry.
   * @return The updated work list.
   */
  def add(ref: T, permanent: Boolean) = {
    if (tail == head) {
      tail = new Entry[T](Some(ref), permanent)
      head.next = tail
    } else {
      tail.next = new Entry[T](Some(ref), permanent)
      tail = tail.next
    }
    this
  }

  /**
   * Removes an entry from the work list
   * @param ref The entry.
   * @return True if the entry is removed, false if the entry is not found.
   */
  def remove(ref: T): Boolean = {

    @tailrec
    def remove(parent: Entry[T], entry: Entry[T]): Boolean = {
      if (entry.ref.get == ref) {
        parent.next = entry.next // Remove entry
        if (tail == entry) tail = parent
        entry.isDeleted = true
        true
      } else if (entry.next != null) remove(entry, entry.next)
      else false
    }

    if (head.next == null) false else remove(head, head.next)
  }

  /**
   * Tries to process each entry using the processing function. Stops at the first entry processing succeeds.
   * If the entry is not permanent, the entry is removed.
   * @param processFn The processing function, returns true if processing succeeds.
   * @return true if an entry has been processed, false if no entries are processed successfully.
   */
  def process(processFn: T ⇒ Boolean): Boolean = {

    @tailrec
    def process(parent: Entry[T], entry: Entry[T]): Boolean = {
      val processed = processFn(entry.ref.get)
      if (processed) {
        if (!entry.permanent && !entry.isDeleted) {
          parent.next = entry.next // Remove entry
          if (tail == entry) tail = parent
          entry.isDeleted = true
        }
        true // Handled
      } else if (entry.next != null) process(entry, entry.next)
      else false
    }

    if (head.next == null) false else process(head, head.next)
  }

  /**
   * Appends another WorkList to this WorkList.
   * @param other The other WorkList
   * @return This WorkList
   */
  def addAll(other: WorkList[T]) = {
    if (other.head.next != null) {
      tail.next = other.head.next
      tail = other.tail
    }
    this
  }

  /**
   * Removes all entries from this WorkList
   * @return True if at least one entry is removed. False if none is removed.
   */
  def removeAll() = {
    if (head.next == null) false
    else {
      head.next = null
      tail = head
      true
    }
  }
}
