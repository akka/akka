/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import akka.actor.{ ActorPath, MinimalActorRef, ActorRefProvider, ActorRef }

// FIXME: remove
abstract class UnregisteredActorRef(prov: Any) extends MinimalActorRef {
  /**
   * Returns the path for this actor (from this actor up to the root actor).
   */
  def path: ActorPath = ???

  /**
   * Get a reference to the actor ref provider which created this ref.
   */
  def provider: ActorRefProvider = ???
}