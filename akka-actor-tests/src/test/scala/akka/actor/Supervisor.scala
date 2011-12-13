/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

class Supervisor extends Actor {
  def receive = {
    case x: Props ⇒ sender ! context.actorOf(x)
  }
  override def preRestart(cause: Throwable, msg: Option[Any]) {}
}
