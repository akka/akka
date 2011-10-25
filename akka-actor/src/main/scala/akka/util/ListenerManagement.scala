/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.util.concurrent.ConcurrentSkipListSet
import akka.actor.{ ActorInitializationException, ActorRef }

/**
 * A manager for listener actors. Intended for mixin by observables.
 *
 * @author Martin Krasser
 */
trait ListenerManagement {

  private val listeners = new ConcurrentSkipListSet[ActorRef]

  /**
   * Specifies whether listeners should be started when added and stopped when removed or not
   */
  protected def manageLifeCycleOfListeners: Boolean = true

  /**
   * Adds the <code>listener</code> this this registry's listener list.
   * The <code>listener</code> is started by this method if manageLifeCycleOfListeners yields true.
   */
  def addListener(listener: ActorRef) {
    listeners add listener
  }

  /**
   * Removes the <code>listener</code> this this registry's listener list.
   * The <code>listener</code> is stopped by this method if manageLifeCycleOfListeners yields true.
   */
  def removeListener(listener: ActorRef) {
    listeners remove listener
    if (manageLifeCycleOfListeners) listener.stop()
  }

  /*
   * Returns whether there are any listeners currently
   */
  def hasListeners: Boolean = !listeners.isEmpty

  /**
   * Checks if a specific listener is registered. Pruned eventually when isShutdown==true in notify.
   */
  def hasListener(listener: ActorRef): Boolean = listeners.contains(listener)

  protected[akka] def notifyListeners(message: ⇒ Any) {
    if (hasListeners) {
      val msg = message
      val iterator = listeners.iterator
      while (iterator.hasNext) {
        val listener = iterator.next
        if (listener.isShutdown) iterator.remove()
        else listener ! msg
      }
    }
  }

  /**
   * Execute <code>f</code> with each listener as argument.
   */
  protected[akka] def foreachListener(f: (ActorRef) ⇒ Unit) {
    val iterator = listeners.iterator
    while (iterator.hasNext) {
      val listener = iterator.next
      if (listener.isShutdown) iterator.remove()
      else f(listener)
    }
  }
}
