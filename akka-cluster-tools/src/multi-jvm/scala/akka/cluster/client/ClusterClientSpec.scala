/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.client

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorPath
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
import akka.cluster.pubsub._
import akka.remote.transport.ThrottlerTransportAdapter.Direction

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
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.client.heartbeat-interval = 1s
    akka.cluster.client.acceptable-heartbeat-pause = 3s
    # number-of-contacts must be >= 4 because we shutdown all but one in the end
    akka.cluster.client.number-of-contacts = 4
    """))

  testTransport(on = true)

  class TestService(testActor: ActorRef) extends Actor {
    def receive = {
      case msg ⇒
        testActor forward msg
        sender() ! msg + "-ack"
    }
  }

  class Service extends Actor {
    def receive = {
      case msg ⇒ sender() ! msg
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

  override def initialParticipants = roles.size

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      createReceptionist()
    }
    enterBarrier(from.name + "-joined")
  }

  def createReceptionist(): Unit = ClusterClientReceptionist(system)

  def awaitCount(expected: Int): Unit = {
    awaitAssert {
      DistributedPubSub(system).mediator ! DistributedPubSubMediator.Count
      expectMsgType[Int] should ===(expected)
    }
  }

  var remainingServerRoleNames = Set(first, second, third, fourth)

  def roleName(addr: Address): Option[RoleName] = remainingServerRoleNames.find(node(_).address == addr)

  def initialContacts = (remainingServerRoleNames - first - fourth).map { r ⇒
    node(r) / "system" / "receptionist"
  }

  "A ClusterClient" must {

    "startup cluster" in within(30 seconds) {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)
      runOn(fourth) {
        val service = system.actorOf(Props(classOf[TestService], testActor), "testService")
        ClusterClientReceptionist(system).registerService(service)
      }
      runOn(first, second, third, fourth) {
        awaitCount(1)
      }

      enterBarrier("after-1")
    }

    "communicate to actor on any node in cluster" in within(10 seconds) {
      runOn(client) {
        val c = system.actorOf(ClusterClient.props(
          ClusterClientSettings(system).withInitialContacts(initialContacts)), "client1")
        c ! ClusterClient.Send("/user/testService", "hello", localAffinity = true)
        expectMsg("hello-ack")
        system.stop(c)
      }
      runOn(fourth) {
        expectMsg("hello")
      }

      enterBarrier("after-2")
    }

    "demonstrate usage" in within(15 seconds) {
      def host1 = first
      def host2 = second
      def host3 = third

      //#server
      runOn(host1) {
        val serviceA = system.actorOf(Props[Service], "serviceA")
        ClusterClientReceptionist(system).registerService(serviceA)
      }

      runOn(host2, host3) {
        val serviceB = system.actorOf(Props[Service], "serviceB")
        ClusterClientReceptionist(system).registerService(serviceB)
      }
      //#server

      runOn(host1, host2, host3, fourth) {
        awaitCount(4)
      }
      enterBarrier("services-replicated")

      //#client
      runOn(client) {
        val c = system.actorOf(ClusterClient.props(
          ClusterClientSettings(system).withInitialContacts(initialContacts)), "client")
        c ! ClusterClient.Send("/user/serviceA", "hello", localAffinity = true)
        c ! ClusterClient.SendToAll("/user/serviceB", "hi")
      }
      //#client

      runOn(client) {
        // note that "hi" was sent to 2 "serviceB"
        receiveN(3).toSet should ===(Set("hello", "hi"))
      }

      lazy val docOnly = { //not used, only demo
        //#initialContacts
        val initialContacts = Set(
          ActorPath.fromString("akka.tcp://OtherSys@host1:2552/system/receptionist"),
          ActorPath.fromString("akka.tcp://OtherSys@host2:2552/system/receptionist"))
        val settings = ClusterClientSettings(system)
          .withInitialContacts(initialContacts)
        //#initialContacts
      }

      // strange, barriers fail without this sleep
      Thread.sleep(1000)
      enterBarrier("after-3")
    }

    "re-establish connection to another receptionist when server is shutdown" in within(30 seconds) {
      runOn(first, second, third, fourth) {
        val service2 = system.actorOf(Props(classOf[TestService], testActor), "service2")
        ClusterClientReceptionist(system).registerService(service2)
        awaitCount(8)
      }
      enterBarrier("service2-replicated")

      runOn(client) {
        val c = system.actorOf(ClusterClient.props(
          ClusterClientSettings(system).withInitialContacts(initialContacts)), "client2")

        c ! ClusterClient.Send("/user/service2", "bonjour", localAffinity = true)
        expectMsg("bonjour-ack")
        val lastSenderAddress = lastSender.path.address
        val receptionistRoleName = roleName(lastSenderAddress) match {
          case Some(r) ⇒ r
          case None    ⇒ fail("unexpected missing roleName: " + lastSender.path.address)
        }
        testConductor.exit(receptionistRoleName, 0).await
        remainingServerRoleNames -= receptionistRoleName
        within(remaining - 3.seconds) {
          awaitAssert {
            c ! ClusterClient.Send("/user/service2", "hi again", localAffinity = true)
            expectMsg(1 second, "hi again-ack")
          }
        }
        system.stop(c)
      }
      enterBarrier("verifed-3")
      receiveWhile(2 seconds) {
        case "hi again" ⇒
        case other      ⇒ fail("unexpected message: " + other)
      }
      enterBarrier("after-4")
    }

    "re-establish connection to receptionist after partition" in within(30 seconds) {
      runOn(client) {
        val c = system.actorOf(ClusterClient.props(
          ClusterClientSettings(system).withInitialContacts(initialContacts)), "client3")

        c ! ClusterClient.Send("/user/service2", "bonjour2", localAffinity = true)
        expectMsg("bonjour2-ack")
        val lastSenderAddress = lastSender.path.address
        val receptionistRoleName = roleName(lastSenderAddress) match {
          case Some(r) ⇒ r
          case None    ⇒ fail("unexpected missing roleName: " + lastSender.path.address)
        }
        // shutdown all but the one that the client is connected to
        remainingServerRoleNames.foreach { r ⇒
          if (r != receptionistRoleName)
            testConductor.exit(r, 0).await
        }
        remainingServerRoleNames = Set(receptionistRoleName)
        // network partition between client and server
        testConductor.blackhole(client, receptionistRoleName, Direction.Both).await
        c ! ClusterClient.Send("/user/service2", "ping", localAffinity = true)
        // if we would use remote watch the failure detector would trigger and
        // connection quarantined
        expectNoMsg(5 seconds)

        testConductor.passThrough(client, receptionistRoleName, Direction.Both).await

        val expectedAddress = node(receptionistRoleName).address
        awaitAssert {
          c ! ClusterClient.Send("/user/service2", "bonjour3", localAffinity = true)
          expectMsg(1 second, "bonjour3-ack")
          val lastSenderAddress = lastSender.path.address
          lastSenderAddress should be(expectedAddress)
        }
        system.stop(c)
      }

      enterBarrier("after-5")
    }

  }
}
