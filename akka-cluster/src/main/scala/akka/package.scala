/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import akka.actor.ActorSystem

package object cluster {

  /**
   * Implicitly creates an augmented [[akka.actor.ActorSystem]] with a method {{{def node: Node}}}.
   *
   * @param system
   * @return An augmented [[akka.actor.ActorSystem]] with a method {{{def node: Node}}}.
   */
  implicit def actorSystemWithNodeAccessor(system: ActorSystem) = new {
    val node = NodeExtension(system)
  }
}
