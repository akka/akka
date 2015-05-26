/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import language.reflectiveCalls
import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.ExtendedActorSystem
import akka.actor.Address
import akka.cluster.InternalClusterAction._
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import akka.actor.ActorRef
import akka.testkit.TestProbe

object ClusterSpec {
  val config = """
    akka.cluster {
      auto-down-unreachable-after = 0s
      periodic-tasks-initial-delay = 120 seconds // turn off scheduled tasks
      publish-stats-interval = 0 s # always, when it happens
      failure-detector.implementation-class = akka.cluster.FailureDetectorPuppet
    }
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.netty.tcp.port = 0
    # akka.loglevel = DEBUG
    """

  case class GossipTo(address: Address)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterSpec extends AkkaSpec(ClusterSpec.config) with ImplicitSender {
  import ClusterSpec._

  val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  val cluster = Cluster(system)
  def clusterView = cluster.readView

  def leaderActions(): Unit =
    cluster.clusterCore ! LeaderActionsTick

  "A Cluster" must {

    "use the address of the remote transport" in {
      cluster.selfAddress should be(selfAddress)
    }

    "register jmx mbean" in {
      val name = new ObjectName("akka:type=Cluster")
      val info = ManagementFactory.getPlatformMBeanServer.getMBeanInfo(name)
      info.getAttributes.length should be > (0)
      info.getOperations.length should be > (0)
    }

    "initially become singleton cluster when joining itself and reach convergence" in {
      clusterView.members.size should be(0)
      cluster.join(selfAddress)
      leaderActions() // Joining -> Up
      awaitCond(clusterView.isSingletonCluster)
      clusterView.self.address should be(selfAddress)
      clusterView.members.map(_.address) should be(Set(selfAddress))
      awaitAssert(clusterView.status should be(MemberStatus.Up))
    }

    "publish inital state as snapshot to subscribers" in {
      try {
        cluster.subscribe(testActor, ClusterEvent.InitialStateAsSnapshot, classOf[ClusterEvent.MemberEvent])
        expectMsgClass(classOf[ClusterEvent.CurrentClusterState])
      } finally {
        cluster.unsubscribe(testActor)
      }
    }

    "publish inital state as events to subscribers" in {
      try {
        cluster.subscribe(testActor, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.MemberEvent])
        expectMsgClass(classOf[ClusterEvent.MemberUp])
      } finally {
        cluster.unsubscribe(testActor)
      }
    }

    "publish CurrentClusterState to subscribers when requested" in {
      try {
        cluster.subscribe(testActor, classOf[ClusterEvent.ClusterDomainEvent], classOf[ClusterEvent.CurrentClusterState])
        // first, is in response to the subscription
        expectMsgClass(classOf[ClusterEvent.CurrentClusterState])

        cluster.publishCurrentClusterState()
        expectMsgClass(classOf[ClusterEvent.CurrentClusterState])
      } finally {
        cluster.unsubscribe(testActor)
      }
    }

    "send CurrentClusterState to one receiver when requested" in {
      cluster.sendCurrentClusterState(testActor)
      expectMsgClass(classOf[ClusterEvent.CurrentClusterState])
    }

    // this should be the last test step, since the cluster is shutdown
    "publish MemberRemoved when shutdown" in {
      val callbackProbe = TestProbe()
      cluster.registerOnMemberRemoved(callbackProbe.ref ! "OnMemberRemoved")

      cluster.subscribe(testActor, classOf[ClusterEvent.MemberRemoved])
      // first, is in response to the subscription
      expectMsgClass(classOf[ClusterEvent.CurrentClusterState])

      cluster.shutdown()
      expectMsgType[ClusterEvent.MemberRemoved].member.address should be(selfAddress)

      callbackProbe.expectMsg("OnMemberRemoved")
    }

  }
}
