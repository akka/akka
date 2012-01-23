/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

class Supervisor extends Actor {
  def receive = {
    case x: Props ⇒ sender ! context.actorOf(x)
  }
  // need to override the default of stopping all children upon restart, tests rely on keeping them around
  override def preRestart(cause: Throwable, msg: Option[Any]) {}
}
