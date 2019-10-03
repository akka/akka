/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.singleton

import akka.actor.{ Actor, ActorIdentity, ActorLogging, ActorRef, Address, Identify, PoisonPill, Props }
import akka.cluster.MemberStatus.Up
import akka.cluster.TestLeaseActor._
import akka.cluster.singleton.ClusterSingletonManagerLeaseSpec.ImportantSingleton.Response
import akka.cluster._
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object ClusterSingletonManagerLeaseSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  testTransport(true)

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.downing-provider-class = akka.cluster.testkit.AutoDowning
    akka.cluster.testkit.auto-down-unreachable-after = 0s
    test-lease {
        lease-class = akka.cluster.TestLeaseActorClient
        heartbeat-interval = 1s
        heartbeat-timeout = 120s
        lease-operation-timeout = 3s
   }
   akka.cluster.singleton {
    use-lease = "test-lease"
   }
                                          """))

  nodeConfig(first, second, third)(ConfigFactory.parseString("akka.cluster.roles = [worker]"))

  object ImportantSingleton {
    case class Response(msg: Any, address: Address) extends JavaSerializable

    def props(): Props = Props(new ImportantSingleton())
  }

  class ImportantSingleton extends Actor with ActorLogging {
    val selfAddress = Cluster(context.system).selfAddress
    override def preStart(): Unit = {
      log.info("Singleton starting")
    }
    override def postStop(): Unit = {
      log.info("Singleton stopping")
    }
    override def receive: Receive = {
      case msg =>
        sender() ! Response(msg, selfAddress)
    }
  }
}

class ClusterSingletonManagerLeaseMultiJvmNode1 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode2 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode3 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode4 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode5 extends ClusterSingletonManagerLeaseSpec

class ClusterSingletonManagerLeaseSpec
    extends MultiNodeSpec(ClusterSingletonManagerLeaseSpec)
    with STMultiNodeSpec
    with ImplicitSender
    with MultiNodeClusterSpec {

  import ClusterSingletonManagerLeaseSpec.ImportantSingleton._
  import ClusterSingletonManagerLeaseSpec._

  override def initialParticipants = roles.size

  // used on the controller
  val leaseProbe = TestProbe()

  "Cluster singleton manager with lease" should {

    "form a cluster" in {
      awaitClusterUp(controller, first)
      enterBarrier("initial-up")
      runOn(second) {
        joinWithin(first)
        awaitAssert({
          cluster.state.members.toList.map(_.status) shouldEqual List(Up, Up, Up)
        }, 10.seconds)
      }
      enterBarrier("second-up")
      runOn(third) {
        joinWithin(first)
        awaitAssert({
          cluster.state.members.toList.map(_.status) shouldEqual List(Up, Up, Up, Up)
        }, 10.seconds)
      }
      enterBarrier("third-up")
      runOn(fourth) {
        joinWithin(first)
        awaitAssert({
          cluster.state.members.toList.map(_.status) shouldEqual List(Up, Up, Up, Up, Up)
        }, 10.seconds)
      }
      enterBarrier("fourth-up")
    }

    "start test lease" in {
      runOn(controller) {
        system.actorOf(TestLeaseActor.props(), s"lease-${system.name}")
      }
      enterBarrier("lease-actor-started")
    }

    "find the lease on every node" in {
      system.actorSelection(node(controller) / "user" / s"lease-${system.name}") ! Identify(None)
      val leaseRef: ActorRef = expectMsgType[ActorIdentity].ref.get
      TestLeaseActorClientExt(system).setActorLease(leaseRef)
      enterBarrier("singleton-started")
    }

    "Start singleton and ping from all nodes" in {
      runOn(first, second, third, fourth) {
        system.actorOf(
          ClusterSingletonManager
            .props(props(), PoisonPill, ClusterSingletonManagerSettings(system).withRole("worker")),
          "important")
      }
      enterBarrier("singleton-started")

      val proxy = system.actorOf(
        ClusterSingletonProxy.props(
          singletonManagerPath = "/user/important",
          settings = ClusterSingletonProxySettings(system).withRole("worker")))

      runOn(first, second, third, fourth) {
        proxy ! "Ping"
        // lease has not been granted so now allowed to come up
        expectNoMessage(2.seconds)
      }

      enterBarrier("singleton-pending")

      runOn(controller) {
        TestLeaseActorClientExt(system).getLeaseActor() ! GetRequests
        expectMsg(LeaseRequests(List(Acquire(address(first).hostPort))))
        TestLeaseActorClientExt(system).getLeaseActor() ! ActionRequest(Acquire(address(first).hostPort), true)
      }
      enterBarrier("lease-acquired")

      runOn(first, second, third, fourth) {
        expectMsg(Response("Ping", address(first)))
      }
      enterBarrier("pinged")
    }

    "Move singleton when oldest node downed" in {

      cluster.state.members.size shouldEqual 5
      runOn(controller) {
        cluster.down(address(first))
        awaitAssert({
          cluster.state.members.toList.map(_.status) shouldEqual List(Up, Up, Up, Up)
        }, 20.seconds)
        val requests = awaitAssert({
          TestLeaseActorClientExt(system).getLeaseActor() ! GetRequests
          val msg = expectMsgType[LeaseRequests]
          withClue("Requests: " + msg) {
            msg.requests.size shouldEqual 2
          }
          msg
        }, 10.seconds)

        requests.requests should contain(Release(address(first).hostPort))
        requests.requests should contain(Acquire(address(second).hostPort))
      }
      runOn(second, third, fourth) {
        awaitAssert({
          cluster.state.members.toList.map(_.status) shouldEqual List(Up, Up, Up, Up)
        }, 20.seconds)
      }
      enterBarrier("first node downed")
      val proxy = system.actorOf(
        ClusterSingletonProxy.props(
          singletonManagerPath = "/user/important",
          settings = ClusterSingletonProxySettings(system).withRole("worker")))

      runOn(second, third, fourth) {
        proxy ! "Ping"
        // lease has not been granted so now allowed to come up
        expectNoMessage(2.seconds)
      }
      enterBarrier("singleton-not-migrated")

      runOn(controller) {
        TestLeaseActorClientExt(system).getLeaseActor() ! ActionRequest(Acquire(address(second).hostPort), true)
      }

      enterBarrier("singleton-moved-to-second")

      runOn(second, third, fourth) {
        proxy ! "Ping"
        expectMsg(Response("Ping", address(second)))
      }
      enterBarrier("finished")
    }
  }
}
