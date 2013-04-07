/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.Address

object ClusterClientSpec extends MultiNodeConfig {
  val client = role("client")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-join = off
    akka.cluster.auto-down = on
    """))

  class TestService(testActor: ActorRef) extends Actor {
    def receive = {
      case msg ⇒
        testActor forward msg
        sender ! "ack"
    }
  }

}

class ClusterClientMultiJvmNode1 extends ClusterClientSpec
class ClusterClientMultiJvmNode2 extends ClusterClientSpec
class ClusterClientMultiJvmNode3 extends ClusterClientSpec
class ClusterClientMultiJvmNode4 extends ClusterClientSpec
class ClusterClientMultiJvmNode5 extends ClusterClientSpec

class ClusterClientSpec extends MultiNodeSpec(ClusterClientSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterClientSpec._
  import DistributedPubSubMediator._

  override def initialParticipants = roles.size

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      createReceptionist()
    }
    enterBarrier(from.name + "-joined")
  }

  def createReceptionist(): ActorRef = ClusterReceptionistExtension(system).receptionist

  def mediator: ActorRef = ClusterReceptionistExtension(system).pubSubMediator
  def receptionist: ActorRef = ClusterReceptionistExtension(system).receptionist

  def awaitCount(expected: Int): Unit = {
    awaitAssert {
      mediator ! Count
      expectMsgType[Int] must be(expected)
    }
  }

  def roleName(addr: Address): Option[RoleName] = roles.find(node(_).address == addr)

  def initialContacts = Set(
    system.actorFor(node(second) / "user" / "receptionist"),
    system.actorFor(node(third) / "user" / "receptionist"))

  "A ClusterClient" must {

    "startup cluster" in within(30 seconds) {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)
      runOn(fourth) {
        val service = system.actorOf(Props(new TestService(testActor)), "service")
        mediator ! Put(service)
      }
      runOn(first, second, third, fourth) {
        awaitCount(1)
      }

      enterBarrier("after-1")
    }

    "communicate to actor on any node in cluster" in within(10 seconds) {
      runOn(client) {
        val c = system.actorOf(Props(new ClusterClient(initialContacts)))

        awaitAssert {
          c ! Send("/user/service", "hello", localAffinity = true)
          expectMsg(1 second, "ack")
        }
      }
      runOn(fourth) {
        expectMsg("hello")
      }

      enterBarrier("after-2")
    }

    "re-establish connection to receptionist when connection is lost" in within(30 seconds) {
      runOn(first, second, third, fourth) {
        val service2 = system.actorOf(Props(new TestService(testActor)), "service2")
        mediator ! Put(service2)
        awaitCount(5)
      }
      enterBarrier("service2-replicated")

      runOn(client) {
        val c = system.actorOf(Props(new ClusterClient(initialContacts)))

        awaitAssert {
          c ! Send("/user/service2", "bonjour", localAffinity = true)
          expectMsg(1 second, "ack")
        }
        val lastSenderAddress = lastSender.path.address
        val receptionistRoleName = roleName(lastSenderAddress) match {
          case Some(r) ⇒ r
          case None    ⇒ fail("unexpected missing roleName: " + lastSender.path.address)
        }
        testConductor.shutdown(receptionistRoleName, 0).await
        awaitAssert {
          c ! Send("/user/service2", "hi again", localAffinity = true)
          expectMsg(1 second, "ack")
        }
      }
      enterBarrier("verifed-3")
      receiveWhile(2 seconds) {
        case "hi again" ⇒
        case other      ⇒ fail("unexpected message: " + other)
      }
      enterBarrier("after-3")
    }

  }
}
