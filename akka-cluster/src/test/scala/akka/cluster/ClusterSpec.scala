/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.ExtendedActorSystem
import akka.actor.Address
import akka.cluster.InternalClusterAction._
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import akka.testkit.TestProbe
import akka.actor.ActorSystem
import akka.actor.Props
import com.typesafe.config.ConfigFactory
import akka.actor.CoordinatedShutdown
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent._
import scala.concurrent.Await

object ClusterSpec {
  val config = """
    akka.cluster {
      auto-down-unreachable-after = 0s
      periodic-tasks-initial-delay = 120 seconds // turn off scheduled tasks
      publish-stats-interval = 0 s # always, when it happens
      failure-detector.implementation-class = akka.cluster.FailureDetectorPuppet
    }
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    """

  final case class GossipTo(address: Address)
}

class ClusterSpec extends AkkaSpec(ClusterSpec.config) with ImplicitSender {

  val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  val cluster = Cluster(system)
  def clusterView = cluster.readView

  def leaderActions(): Unit =
    cluster.clusterCore ! LeaderActionsTick

  "A Cluster" must {

    "use the address of the remote transport" in {
      cluster.selfAddress should ===(selfAddress)
    }

    "register jmx mbean" in {
      val name = new ObjectName("akka:type=Cluster")
      val info = ManagementFactory.getPlatformMBeanServer.getMBeanInfo(name)
      info.getAttributes.length should be > (0)
      info.getOperations.length should be > (0)
    }

    "initially become singleton cluster when joining itself and reach convergence" in {
      clusterView.members.size should ===(0)
      cluster.join(selfAddress)
      leaderActions() // Joining -> Up
      awaitCond(clusterView.isSingletonCluster)
      clusterView.self.address should ===(selfAddress)
      clusterView.members.map(_.address) should ===(Set(selfAddress))
      awaitAssert(clusterView.status should ===(MemberStatus.Up))
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
      expectMsgType[ClusterEvent.MemberRemoved].member.address should ===(selfAddress)

      callbackProbe.expectMsg("OnMemberRemoved")
    }

    "allow join and leave with local address" in {
      val sys2 = ActorSystem("ClusterSpec2", ConfigFactory.parseString("""
        akka.actor.provider = "cluster"
        akka.remote.netty.tcp.port = 0
        akka.remote.artery.canonical.port = 0
        """))
      try {
        val ref = sys2.actorOf(Props.empty)
        Cluster(sys2).join(ref.path.address) // address doesn't contain full address information
        within(5.seconds) {
          awaitAssert {
            Cluster(sys2).state.members.size should ===(1)
            Cluster(sys2).state.members.head.status should ===(MemberStatus.Up)
          }
        }
        Cluster(sys2).leave(ref.path.address)
        within(5.seconds) {
          awaitAssert {
            Cluster(sys2).isTerminated should ===(true)
          }
        }
      } finally {
        shutdown(sys2)
      }
    }

    "allow to resolve remotePathOf any actor" in {
      val remotePath = cluster.remotePathOf(testActor)

      testActor.path.address.host should ===(None)
      cluster.remotePathOf(testActor).uid should ===(testActor.path.uid)
      cluster.remotePathOf(testActor).address should ===(selfAddress)
    }

    "leave via CoordinatedShutdown.run" in {
      val sys2 = ActorSystem("ClusterSpec2", ConfigFactory.parseString("""
        akka.actor.provider = "cluster"
        akka.remote.netty.tcp.port = 0
        akka.remote.artery.canonical.port = 0
        """))
      try {
        val probe = TestProbe()(sys2)
        Cluster(sys2).subscribe(probe.ref, classOf[MemberEvent])
        probe.expectMsgType[CurrentClusterState]
        Cluster(sys2).join(Cluster(sys2).selfAddress)
        probe.expectMsgType[MemberUp]

        CoordinatedShutdown(sys2).run()
        probe.expectMsgType[MemberLeft]
        probe.expectMsgType[MemberExited]
        probe.expectMsgType[MemberRemoved]
      } finally {
        shutdown(sys2)
      }
    }

    "terminate ActorSystem via leave (CoordinatedShutdown)" in {
      val sys2 = ActorSystem("ClusterSpec2", ConfigFactory.parseString("""
        akka.actor.provider = "cluster"
        akka.remote.netty.tcp.port = 0
        akka.remote.artery.canonical.port = 0
        akka.coordinated-shutdown.terminate-actor-system = on
        """))
      try {
        val probe = TestProbe()(sys2)
        Cluster(sys2).subscribe(probe.ref, classOf[MemberEvent])
        probe.expectMsgType[CurrentClusterState]
        Cluster(sys2).join(Cluster(sys2).selfAddress)
        probe.expectMsgType[MemberUp]

        Cluster(sys2).leave(Cluster(sys2).selfAddress)
        probe.expectMsgType[MemberLeft]
        probe.expectMsgType[MemberExited]
        probe.expectMsgType[MemberRemoved]
        Await.result(sys2.whenTerminated, 10.seconds)
        Cluster(sys2).isTerminated should ===(true)
      } finally {
        shutdown(sys2)
      }
    }

    "terminate ActorSystem via down (CoordinatedShutdown)" in {
      val sys3 = ActorSystem("ClusterSpec3", ConfigFactory.parseString("""
        akka.actor.provider = "cluster"
        akka.remote.netty.tcp.port = 0
        akka.remote.artery.canonical.port = 0
        akka.coordinated-shutdown.terminate-actor-system = on
        akka.cluster.run-coordinated-shutdown-when-down = on
akka.loglevel=DEBUG
        """))
      try {
        val probe = TestProbe()(sys3)
        Cluster(sys3).subscribe(probe.ref, classOf[MemberEvent])
        probe.expectMsgType[CurrentClusterState]
        Cluster(sys3).join(Cluster(sys3).selfAddress)
        probe.expectMsgType[MemberUp]

        Cluster(sys3).down(Cluster(sys3).selfAddress)
        probe.expectMsgType[MemberRemoved]
        Await.result(sys3.whenTerminated, 10.seconds)
        Cluster(sys3).isTerminated should ===(true)
      } finally {
        shutdown(sys3)
      }
    }
  }
}
