/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import se.scalablesolutions.akka.actor.{ActorRef, Actor}

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer { self: Actor =>
  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def endpointUri: String

  /**
   * Determines whether two-way communications with this consumer actor should
   * be done in blocking or non-blocking mode (default is non-blocking). One-way
   * communications never block.
   */
  def blocking = false
}

/**
 * @author Martin Krasser
 */
private[camel] object Consumer {
  /**
   * Applies a function <code>f</code> to <code>actorRef</code> if <code>actorRef</code>
   * references a consumer actor. A valid reference to a consumer actor is a local actor
   * reference with a target actor that implements the <code>Consumer</code> trait. The
   * target <code>Consumer</code> object is passed as argument to <code>f</code>. This
   * method returns <code>None</code> if <code>actorRef</code> is not a valid reference
   * to a consumer actor, <code>Some</code> result otherwise.
   */
  def forConsumer[T](actorRef: ActorRef)(f: Consumer => T): Option[T] = {
    if (!actorRef.actor.isInstanceOf[Consumer]) None
    else if (actorRef.remoteAddress.isDefined) None
    else Some(f(actorRef.actor.asInstanceOf[Consumer]))
  }
}