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
  private val expectMap = WorkMap.empty[Actor.Receive]
  private val addBuffer = WorkMap.empty[Actor.Receive]

  /**
   * Adds the partial function to the receive set, to be removed on first match.
   * @param fn The receive function.
   * @return The same receive function.
   */
  def expectOnce(fn: Actor.Receive): Actor.Receive = {
    if (processing) addBuffer.add(fn, permanent = false)
    else expectMap.add(fn, permanent = false)
    fn
  }

  /**
   * Adds the partial function to the receive set and keeping it in the receive set till removed.
   * @param fn The receive function.
   * @return The same receive function.
   */
  def expect(fn: Actor.Receive): Actor.Receive = {
    if (processing) addBuffer.add(fn, permanent = true)
    else expectMap.add(fn, permanent = true)
    fn
  }

  /**
   * Removes the partial function from the receive set.
   * @param fn The receive function.
   * @return True if the partial function is removed, false if not found.
   */
  def unexpect(fn: Actor.Receive): Boolean = {
    if (expectMap.remove(fn)) true
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
      expectMap.process { fn ⇒
        var processed = true
        fn.applyOrElse(msg, (_: Any) ⇒ processed = false)
        processed
      }
    } finally {
      processing = false
      expectMap.addAll(addBuffer)
      addBuffer.removeAll()
    }
  }
}

object WorkMap {
  class Status(val permanent: Boolean) { var isDeleted = false }
  def empty[A]: WorkMap[A] = new WorkMap[A]
}

class WorkMap[A] {
  import WorkMap._

  private val underlying = mutable.LinkedHashMap.empty[A, Status]

  def add(item: A, permanent: Boolean): WorkMap[A] = {
    underlying += item -> new Status(permanent)
    this
  }

  def remove(item: A): Boolean = {
    underlying.get(item) match {
      case Some(status) ⇒
        status.isDeleted = true
        underlying -= item
        true
      case None ⇒ false
    }
  }

  def process(f: A ⇒ Boolean): Boolean = {
    underlying.find { case (item, _) ⇒ f(item) } match {
      case Some((recv, status)) ⇒
        if (!status.permanent && !status.isDeleted) {
          underlying -= recv
          status.isDeleted = true
        }
        true
      case None ⇒ false
    }
  }

  def addAll(other: WorkMap[A]): WorkMap[A] = {
    underlying ++= other.underlying
    this
  }

  def removeAll(): WorkMap[A] = {
    underlying.clear()
    this
  }

  def size: Int = underlying.size
}
