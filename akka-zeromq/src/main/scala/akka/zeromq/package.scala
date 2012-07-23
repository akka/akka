/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import language.implicitConversions

import actor.ActorSystem

/**
 * A package object with an implicit conversion for the actor system as a convenience
 */
package object zeromq {
  /**
   * Convenience accessor to subscribe to all events
   */
  val SubscribeAll: Subscribe = Subscribe.all

  /**
   * Set the linger to 0, doesn't block and discards messages that haven't been sent yet.
   */
  val NoLinger: Linger = Linger.no
}