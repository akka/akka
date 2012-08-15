/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import language.reflectiveCalls

import scala.concurrent.util.duration._
import scala.concurrent.util.Duration

import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.ExtendedActorSystem
import akka.actor.Address
import akka.cluster.InternalClusterAction._
import akka.remote.RemoteActorRefProvider
import java.lang.management.ManagementFactory
import javax.management.ObjectName

object ClusterSpec {
  val config = """
    akka.cluster {
      auto-join                    = off
      auto-down                    = off
      periodic-tasks-initial-delay = 120 seconds // turn off scheduled tasks
      publish-stats-interval = 0 s # always, when it happens
    }
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.port = 0
    # akka.loglevel = DEBUG
    """

  case class GossipTo(address: Address)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterSpec extends AkkaSpec(ClusterSpec.config) with ImplicitSender {
  import ClusterSpec._

  val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.address

  val failureDetector = new FailureDetectorPuppet(system)

  val cluster = new Cluster(system.asInstanceOf[ExtendedActorSystem], failureDetector)

  def leaderActions(): Unit = {
    cluster.clusterCore ! LeaderActionsTick
    awaitPing()
  }

  def awaitPing(): Unit = {
    val ping = Ping()
    cluster.clusterCore ! ping
    expectMsgPF() { case pong @ Pong(`ping`, _) ⇒ pong }
  }

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
      cluster.members.size must be(0) // auto-join = off
      cluster.join(selfAddress)
      Thread.sleep(5000)
      awaitCond(cluster.isSingletonCluster)
      cluster.self.address must be(selfAddress)
      cluster.members.map(_.address) must be(Set(selfAddress))
      cluster.status must be(MemberStatus.Joining)
      cluster.convergence must be(true)
      leaderActions()
      cluster.status must be(MemberStatus.Up)
    }

  }
}
