/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import java.util.concurrent.CopyOnWriteArrayList

import se.scalablesolutions.akka.actor.ActorRef

/**
 * A manager for listener actors. Intended for mixin by observables.
 *
 * @author Martin Krasser
 */
trait ListenerManagement extends Logging {

  private val listeners = new CopyOnWriteArrayList[ActorRef]

  /**
   * Adds the <code>listener</code> this this registry's listener list.
   * The <code>listener</code> is started by this method.
   */
  def addListener(listener: ActorRef) = {
    listener.start
    listeners.add(listener)
  }

  /**
   * Removes the <code>listener</code> this this registry's listener list.
   * The <code>listener</code> is stopped by this method.
   */
  def removeListener(listener: ActorRef) = {
    listener.stop
    listeners.remove(listener)
  }

  /**
   * Execute <code>f</code> with each listener as argument.
   */
  protected def foreachListener(f: (ActorRef) => Unit) {
    val iterator = listeners.iterator
    while (iterator.hasNext) {
      val listener = iterator.next
      if (listener.isRunning) f(listener)
      else log.warning("Can't notify [%s] since it is not running.", listener)
    }
  }
}
