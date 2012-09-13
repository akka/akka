/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import scala.concurrent.Await

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope
import akka.testkit.AkkaSpec
import akka.testkit._

object ConsistentHashingRouterSpec {

  val config = """
    akka.actor.deployment {
      /router1 {
        router = consistent-hashing
        nr-of-instances = 3
        virtual-nodes-factor = 17
      }
    }
    """

  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self
    }
  }

  case class Msg(key: Any, data: String) extends ConsistentHashable {
    override def consistentHashKey = key
  }

  case class MsgKey(name: String)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConsistentHashingRouterSpec extends AkkaSpec(ConsistentHashingRouterSpec.config) with DefaultTimeout with ImplicitSender {
  import akka.routing.ConsistentHashingRouterSpec._
  implicit val ec = system.dispatcher

  val router1 = system.actorOf(Props[Echo].withRouter(ConsistentHashingRouter()), "router1")

  "consistent hashing router" must {
    "create routees from configuration" in {
      val currentRoutees = Await.result(router1 ? CurrentRoutees, remaining).asInstanceOf[RouterRoutees]
      currentRoutees.routees.size must be(3)
    }

    "select destination based on consistentHashKey of the message" in {
      router1 ! Msg("a", "A")
      val destinationA = expectMsgPF(remaining) { case ref: ActorRef ⇒ ref }
      router1 ! ConsistentHashableEnvelope(message = "AA", consistentHashKey = "a")
      expectMsg(destinationA)

      router1 ! Msg(17, "B")
      val destinationB = expectMsgPF(remaining) { case ref: ActorRef ⇒ ref }
      router1 ! ConsistentHashableEnvelope(message = "BB", consistentHashKey = 17)
      expectMsg(destinationB)

      router1 ! Msg(MsgKey("c"), "C")
      val destinationC = expectMsgPF(remaining) { case ref: ActorRef ⇒ ref }
      router1 ! Msg(MsgKey("c"), "CC")
      expectMsg(destinationC)
    }
  }

}
