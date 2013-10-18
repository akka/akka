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

  class ReceiveStatus(val permanent: Boolean) { var isDeleted = false }
  type ReceiveStatusMap = mutable.LinkedHashMap[Actor.Receive, ReceiveStatus]

  private var processing = false
  private val expectMap: ReceiveStatusMap = mutable.LinkedHashMap.empty
  private val addBuffer: ReceiveStatusMap = mutable.LinkedHashMap.empty

  /**
   * Adds the partial function to the receive set, to be removed on first match.
   * @param fn The receive function.
   * @return The same receive function.
   */
  def expectOnce(fn: Actor.Receive): Actor.Receive = {
    if (processing) addBuffer += fn -> new ReceiveStatus(permanent = false)
    else expectMap += fn -> new ReceiveStatus(permanent = false)
    fn
  }

  /**
   * Adds the partial function to the receive set and keeping it in the receive set till removed.
   * @param fn The receive function.
   * @return The same receive function.
   */
  def expect(fn: Actor.Receive): Actor.Receive = {
    if (processing) addBuffer += fn -> new ReceiveStatus(permanent = true)
    else expectMap += fn -> new ReceiveStatus(permanent = true)
    fn
  }

  /**
   * Removes the partial function from the receive set.
   * @param fn The receive function.
   * @return True if the partial function is removed, false if not found.
   */
  def unexpect(fn: Actor.Receive): Boolean = {
    if (remove(fn, expectMap)) true
    else if (processing && remove(fn, addBuffer)) true
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
      process { fn ⇒
        var processed = true
        fn.applyOrElse(msg, (_: Any) ⇒ processed = false)
        processed
      }
    } finally {
      processing = false
      expectMap ++= addBuffer
      addBuffer.clear()
    }
  }

  private def remove(fn: Actor.Receive, map: ReceiveStatusMap): Boolean = {
    map.get(fn) match {
      case Some(status) ⇒
        status.isDeleted = true
        map -= fn
        true
      case None ⇒ false
    }
  }

  private def process(f: Actor.Receive ⇒ Boolean): Boolean = {
    expectMap.find { case (fn, _) ⇒ f(fn) } match {
      case Some((recv, status)) ⇒
        if (!status.permanent && !status.isDeleted) {
          expectMap -= recv
          status.isDeleted = true
        }
        true
      case None ⇒ false
    }
  }
}
