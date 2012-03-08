/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import akka.actor.{ Actor, Props }
import java.util.UUID

class AMQPActor extends Actor {

  def receive = {
    case cr: ConnectionRequest ⇒ {
      val connection = context.actorOf(Props(new FaultTolerantConnectionActor(cr.connectionParameters)), "amqp-" + UUID.randomUUID.toString)
      connection ! Connect
      sender ! connection
    }
  }
}
