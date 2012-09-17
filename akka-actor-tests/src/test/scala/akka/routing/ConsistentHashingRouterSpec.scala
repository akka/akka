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
      /router2 {
        router = consistent-hashing
        nr-of-instances = 5
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

  case class Msg2(key: Any, data: String)
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
      val destinationA = expectMsgType[ActorRef]
      router1 ! ConsistentHashableEnvelope(message = "AA", consistentHashKey = "a")
      expectMsg(destinationA)

      router1 ! Msg(17, "B")
      val destinationB = expectMsgType[ActorRef]
      router1 ! ConsistentHashableEnvelope(message = "BB", consistentHashKey = 17)
      expectMsg(destinationB)

      router1 ! Msg(MsgKey("c"), "C")
      val destinationC = expectMsgType[ActorRef]
      router1 ! ConsistentHashableEnvelope(message = "CC", consistentHashKey = MsgKey("c"))
      expectMsg(destinationC)
    }

    "select destination with defined consistentHashRoute" in {
      def consistentHashRoute: ConsistentHashingRouter.ConsistentHashRoute = {
        case Msg2(key, data) ⇒ key
      }
      val router2 = system.actorOf(Props[Echo].withRouter(ConsistentHashingRouter(
        consistentHashRoute = consistentHashRoute)), "router2")

      router2 ! Msg2("a", "A")
      val destinationA = expectMsgType[ActorRef]
      router2 ! ConsistentHashableEnvelope(message = "AA", consistentHashKey = "a")
      expectMsg(destinationA)

      router2 ! Msg2(17, "B")
      val destinationB = expectMsgType[ActorRef]
      router2 ! ConsistentHashableEnvelope(message = "BB", consistentHashKey = 17)
      expectMsg(destinationB)

      router2 ! Msg2(MsgKey("c"), "C")
      val destinationC = expectMsgType[ActorRef]
      router2 ! ConsistentHashableEnvelope(message = "CC", consistentHashKey = MsgKey("c"))
      expectMsg(destinationC)
    }
  }

}
