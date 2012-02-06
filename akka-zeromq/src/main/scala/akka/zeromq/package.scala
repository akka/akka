/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import actor.ActorSystem

/**
 * A package object with an implicit conversion for the actor system as a convenience
 */
package object zeromq {

  /**
   * Creates a zeromq actor system implicitly
   * @param system
   * @return An augmented [[akka.actor.ActorSystem]]
   */
  implicit def zeromqSystem(system: ActorSystem) = system.extension(ZeroMQExtension)

  /**
   * Convenience accessor to subscribe to all events
   */
  val SubscribeAll = Subscribe(Seq.empty)

  /**
   * Set the linger to 0, doesn't block and discards messages that haven't been sent yet.
   */
  val NoLinger = Linger(0)
}