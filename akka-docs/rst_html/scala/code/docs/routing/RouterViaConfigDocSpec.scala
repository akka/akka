/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.routing

import akka.actor.{ Actor, Props, ActorSystem, ActorLogging }
import com.typesafe.config.ConfigFactory
import akka.routing.FromConfig
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

object RouterWithConfigDocSpec {

  val config = ConfigFactory.parseString("""

    //#config-round-robin
    akka.actor.deployment {
      /myrouter1 {
        router = round-robin
        nr-of-instances = 5
      }
    }
    //#config-round-robin

    //#config-resize
    akka.actor.deployment {
      /myrouter2 {
        router = round-robin
        resizer {
          lower-bound = 2
          upper-bound = 15
        }
      }
    }
    //#config-resize

    //#config-random
    akka.actor.deployment {
      /myrouter3 {
        router = random
        nr-of-instances = 5
      }
    }
    //#config-random

    //#config-smallest-mailbox
    akka.actor.deployment {
      /myrouter4 {
        router = smallest-mailbox
        nr-of-instances = 5
      }
    }
    //#config-smallest-mailbox

    //#config-broadcast
    akka.actor.deployment {
      /myrouter5 {
        router = broadcast
        nr-of-instances = 5
      }
    }
    //#config-broadcast

    //#config-scatter-gather
    akka.actor.deployment {
      /myrouter6 {
        router = scatter-gather
        nr-of-instances = 5
        within = 10 seconds
      }
    }
    //#config-scatter-gather

    //#config-consistent-hashing
    akka.actor.deployment {
      /myrouter7 {
        router = consistent-hashing
        nr-of-instances = 5
        virtual-nodes-factor = 10
      }
    }
    //#config-consistent-hashing

    """)

  case class Message(nbr: Int) extends ConsistentHashable {
    override def consistentHashKey = nbr
  }

  class ExampleActor extends Actor with ActorLogging {
    def receive = {
      case Message(nbr) ⇒
        log.debug("Received %s in router %s".format(nbr, self.path.name))
        sender ! nbr
    }
  }

}

class RouterWithConfigDocSpec extends AkkaSpec(RouterWithConfigDocSpec.config) with ImplicitSender {

  import RouterWithConfigDocSpec._

  "demonstrate configured round-robin router" in {
    //#configurableRouting
    val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
      "myrouter1")
    //#configurableRouting
    1 to 10 foreach { i ⇒ router ! Message(i) }
    receiveN(10)
  }

  "demonstrate configured random router" in {
    val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
      "myrouter3")
    1 to 10 foreach { i ⇒ router ! Message(i) }
    receiveN(10)
  }

  "demonstrate configured smallest-mailbox router" in {
    val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
      "myrouter4")
    1 to 10 foreach { i ⇒ router ! Message(i) }
    receiveN(10)
  }

  "demonstrate configured broadcast router" in {
    val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
      "myrouter5")
    1 to 10 foreach { i ⇒ router ! Message(i) }
    receiveN(5 * 10)
  }

  "demonstrate configured scatter-gather router" in {
    val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
      "myrouter6")
    1 to 10 foreach { i ⇒ router ! Message(i) }
    receiveN(10)
  }

  "demonstrate configured consistent-hashing router" in {
    val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
      "myrouter7")
    1 to 10 foreach { i ⇒ router ! Message(i) }
    receiveN(10)
  }

  "demonstrate configured round-robin router with resizer" in {
    //#configurableRoutingWithResizer
    val router = system.actorOf(Props[ExampleActor].withRouter(FromConfig()),
      "myrouter2")
    //#configurableRoutingWithResizer
    1 to 10 foreach { i ⇒ router ! Message(i) }
    receiveN(10)
  }

}