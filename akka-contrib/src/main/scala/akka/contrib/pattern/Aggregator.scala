/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import akka.actor.Actor
import scala.collection.mutable

/**
 * The aggregator is to be mixed into an actor for the aggregator behavior.
 */
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
    if (expectList.remove(fn)) true
    else if (processing && addBuffer.remove(fn)) true
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
      expectList.process { fn ⇒
        var processed = true
        fn.applyOrElse(msg, (_: Any) ⇒ processed = false)
        processed
      }
    } finally {
      processing = false
      expectList.addAll(addBuffer)
      addBuffer.removeAll()
    }
  }
}

object WorkList {
  class Status[A](val item: A, val permanent: Boolean) { var isDeleted = false }
  def empty[A]: WorkList[A] = new WorkList[A]
}

class WorkList[A] {
  import WorkList._

  private val underlying = mutable.ArrayBuffer.empty[Status[A]]

  def add(item: A, permanent: Boolean): WorkList[A] = {
    underlying += new Status(item, permanent)
    this
  }

  def remove(item: A): Boolean = {
    underlying.find { _.item == item } match {
      case Some(status) ⇒
        status.isDeleted = true
        underlying -= status
        true
      case None ⇒ false
    }
  }

  def process(f: A ⇒ Boolean): Boolean = {
    underlying.find { s ⇒ f(s.item) } match {
      case Some(status) ⇒
        if (!status.permanent && !status.isDeleted) {
          underlying -= status
          status.isDeleted = true
        }
        true
      case None ⇒ false
    }
  }

  def addAll(other: WorkList[A]): WorkList[A] = {
    underlying ++= other.underlying
    this
  }

  def removeAll(): WorkList[A] = {
    underlying.clear()
    this
  }

  def size: Int = underlying.size
}
