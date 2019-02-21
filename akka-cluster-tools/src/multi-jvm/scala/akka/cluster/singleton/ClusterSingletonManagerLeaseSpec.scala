/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.singleton

import akka.actor.{ Actor, ActorIdentity, ActorLogging, ActorRef, ActorSelection, Identify, Props, RootActorPath }
import akka.cluster.{ Cluster, MultiNodeClusterSpec, TestLeaseActor, TestLeaseActorClientExt }
import akka.cluster.ClusterEvent._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit.TestEvent._
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.language.postfixOps

object ClusterSingletonManagerLeaseSpec extends MultiNodeConfig {
  val controller = role("controller")
  val observer = role("observer")
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    test-lease {
        lease-class = akka.cluster.TestLeaseActorClient
        heartbeat-interval = 1s
        heartbeat-timeout = 120s
        lease-operation-timeout = 3s
      }
                                          """))

  nodeConfig(first, second, third)(
    ConfigFactory.parseString("akka.cluster.roles =[worker]"))
}

class ClusterSingletonManagerLeaseMultiJvmNode1 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode2 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode3 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode4 extends ClusterSingletonManagerLeaseSpec
class ClusterSingletonManagerLeaseMultiJvmNode5 extends ClusterSingletonManagerLeaseSpec

class ClusterSingletonManagerLeaseSpec extends MultiNodeSpec(ClusterSingletonManagerLeaseSpec)
  with STMultiNodeSpec with ImplicitSender with MultiNodeClusterSpec {

  import ClusterSingletonManagerLeaseSpec._

  override def initialParticipants = roles.size

  "Cluster singleton manager with lease" should {

    "form a cluster" in {
      awaitClusterUp(controller, observer, first, second, third)
    }

    "start test lease" in {
      runOn(controller) {
        system.actorOf(TestLeaseActor.props, s"lease-${system.name}")
      }
    }

    "find the lease on every node" in {
      system.actorSelection(node(controller) / "user" / s"lease-${system.name}") ! Identify(None)
      val leaseRef: ActorRef = expectMsgType[ActorIdentity].ref.get
      TestLeaseActorClientExt(system).getActorLeaseClient().setActorLeaseRef(leaseRef)
    }
  }
}
