/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.osgi.test

import akka.actor.Actor

/**
 * Simple ping-pong actor, used for testing
 */
object PingPong {

  abstract class TestMessage

  case object Ping extends TestMessage
  case object Pong extends TestMessage

  class PongActor extends Actor {
    def receive = {
      case Ping ⇒
        sender() ! Pong
    }
  }

}
