/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
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

object ClusterSpec {
  val config = """
    akka.cluster {
      auto-join                    = off
      auto-down                    = off
      periodic-tasks-initial-delay = 120 seconds // turn off scheduled tasks
      publish-stats-interval = 0 s # always, when it happens
      failure-detector.implementation-class = akka.cluster.FailureDetectorPuppet
    }
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.netty.port = 0
    # akka.loglevel = DEBUG
    """

  case class GossipTo(address: Address)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterSpec extends AkkaSpec(ClusterSpec.config) with ImplicitSender {
  import ClusterSpec._

  // FIXME: temporary workaround. See #2663
  val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[ClusterActorRefProvider].transport.defaultAddress

  val cluster = Cluster(system)
  def clusterView = cluster.readView

  def leaderActions(): Unit =
    cluster.clusterCore ! LeaderActionsTick

  "A Cluster" must {

    "use the address of the remote transport" in {
      cluster.selfAddress must be(selfAddress)
    }

    "register jmx mbean" in {
      val name = new ObjectName("akka:type=Cluster")
      val info = ManagementFactory.getPlatformMBeanServer.getMBeanInfo(name)
      info.getAttributes.length must be > (0)
      info.getOperations.length must be > (0)
    }

    "initially become singleton cluster when joining itself and reach convergence" in {
      clusterView.members.size must be(0) // auto-join = off
      cluster.join(selfAddress)
      Thread.sleep(5000)
      awaitCond(clusterView.isSingletonCluster)
      clusterView.self.address must be(selfAddress)
      clusterView.members.map(_.address) must be(Set(selfAddress))
      clusterView.status must be(MemberStatus.Joining)
      clusterView.convergence must be(true)
      leaderActions()
      awaitCond(clusterView.status == MemberStatus.Up)
    }

    "publish CurrentClusterState to subscribers when requested" in {
      try {
        cluster.subscribe(testActor, classOf[ClusterEvent.ClusterDomainEvent])
        // first, is in response to the subscription
        expectMsgClass(classOf[ClusterEvent.ClusterDomainEvent])

        cluster.publishCurrentClusterState()
        expectMsgClass(classOf[ClusterEvent.ClusterDomainEvent])
      } finally {
        cluster.unsubscribe(testActor)
      }
    }

    "send CurrentClusterState to one receiver when requested" in {
      cluster.sendCurrentClusterState(testActor)
      expectMsgClass(classOf[ClusterEvent.ClusterDomainEvent])
    }

  }
}
