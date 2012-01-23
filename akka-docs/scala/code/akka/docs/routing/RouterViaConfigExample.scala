/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.routing

import akka.actor.{ Actor, Props, ActorSystem }
import com.typesafe.config.ConfigFactory
import akka.routing.FromConfig

case class Message(nbr: Int)

class ExampleActor extends Actor {
  def receive = {
    case Message(nbr) ⇒ println("Received %s in router %s".format(nbr, self.path.name))
  }
}

object RouterWithConfigExample extends App {
  val config = ConfigFactory.parseString("""
    //#config
    akka.actor.deployment {
      /router {
        router = round-robin
        nr-of-instances = 5
      }
    }
    //#config
    //#config-resize
    akka.actor.deployment {
      /router2 {
        router = round-robin
        resizer {
          lower-bound = 2
          upper-bound = 15
        }
      }
    }
    //#config-resize
      """)
  val system = ActorSystem("Example", config)
  //#configurableRouting
  val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
    "router")
  //#configurableRouting
  1 to 10 foreach { i ⇒ router ! Message(i) }

  //#configurableRoutingWithResizer
  val router2 = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
    "router2")
  //#configurableRoutingWithResizer
  1 to 10 foreach { i ⇒ router2 ! Message(i) }
}