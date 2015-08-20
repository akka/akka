/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import akka.actor.{ Actor, ActorSystem, Props }
import com.typesafe.config.ConfigFactory

object Sample extends App {

  val conf = ConfigFactory.parseString(
    """
      |
    """.stripMargin).withFallback(ConfigFactory.load())

  val system = ActorSystem("Main", conf)

  system.actorOf(Props(new Actor {
    /**
     * This defines the initial actor behavior, it must return a partial function
     * with the actor logic.
     */
    override def receive: Receive = {
      case x ⇒ x
    }
  }).withMailbox("akka.actor.mailbox.unbounded-deque-based"))

  readLine()
  system.terminate()
}