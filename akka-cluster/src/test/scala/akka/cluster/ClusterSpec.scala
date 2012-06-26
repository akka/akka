/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import language.reflectiveCalls

import akka.testkit.AkkaSpec
import akka.util.duration._
import akka.util.Duration
import akka.actor.ExtendedActorSystem
import akka.actor.Address
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.BeforeAndAfter
import akka.remote.RemoteActorRefProvider

object ClusterSpec {
  val config = """
    akka.cluster {
      auto-join                    = off
      auto-down                    = off
      periodic-tasks-initial-delay = 120 seconds // turn off scheduled tasks
    }
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.port = 0
    # akka.loglevel = DEBUG
    """

  case class GossipTo(address: Address)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterSpec extends AkkaSpec(ClusterSpec.config) with BeforeAndAfter {
  import ClusterSpec._

  val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.address
  val addresses = IndexedSeq(
    selfAddress,
    Address("akka", system.name, selfAddress.host.get, selfAddress.port.get + 1),
    Address("akka", system.name, selfAddress.host.get, selfAddress.port.get + 2),
    Address("akka", system.name, selfAddress.host.get, selfAddress.port.get + 3),
    Address("akka", system.name, selfAddress.host.get, selfAddress.port.get + 4),
    Address("akka", system.name, selfAddress.host.get, selfAddress.port.get + 5))

  val deterministicRandom = new AtomicInteger

  val failureDetector = new FailureDetectorPuppet(system)

  val cluster = new Cluster(system.asInstanceOf[ExtendedActorSystem], failureDetector) {

    // 3 deputy nodes (addresses index 1, 2, 3)
    override def seedNodes = addresses.slice(1, 4)

    override def selectRandomNode(addresses: IndexedSeq[Address]): Option[Address] = {
      if (addresses.isEmpty) None
      else Some(addresses.toSeq(deterministicRandom.getAndIncrement % addresses.size))
    }

    override def gossipTo(address: Address): Unit = {
      if (address == self.address) {
        super.gossipTo(address)
      }
      // represent the gossip with a message to be used in asserts
      testActor ! GossipTo(address)
    }

    @volatile
    var _gossipToDeputyProbablity = 0.0

    override def gossipToDeputyProbablity(membersSize: Int, unreachableSize: Int, deputySize: Int): Double = {
      if (_gossipToDeputyProbablity < 0.0) super.gossipToDeputyProbablity(membersSize, unreachableSize, deputySize)
      else _gossipToDeputyProbablity
    }

  }

  def memberStatus(address: Address): Option[MemberStatus] =
    cluster.latestGossip.members.collectFirst { case m if m.address == address ⇒ m.status }

  before {
    cluster._gossipToDeputyProbablity = 0.0
    addresses foreach failureDetector.remove
    deterministicRandom.set(0)
  }

  "A Cluster" must {

    "use the address of the remote transport" in {
      cluster.selfAddress must be(selfAddress)
      cluster.self.address must be(selfAddress)
    }

    "initially be singleton cluster and reach convergence immediately" in {
      cluster.isSingletonCluster must be(true)
      cluster.latestGossip.members.map(_.address) must be(Set(selfAddress))
      memberStatus(selfAddress) must be(Some(MemberStatus.Joining))
      cluster.convergence.isDefined must be(true)
      cluster.leaderActions()
      memberStatus(selfAddress) must be(Some(MemberStatus.Up))
    }

    "accept a joining node" in {
      cluster.joining(addresses(1))
      cluster.latestGossip.members.map(_.address) must be(Set(selfAddress, addresses(1)))
      memberStatus(addresses(1)) must be(Some(MemberStatus.Joining))
      cluster.convergence.isDefined must be(false)
    }

    "accept a few more joining nodes" in {
      for (a ← addresses.drop(2)) {
        cluster.joining(a)
        memberStatus(a) must be(Some(MemberStatus.Joining))
      }
      cluster.latestGossip.members.map(_.address) must be(addresses.toSet)
    }

    "order members by host and port" in {
      // note the importance of using toSeq before map, otherwise it will not preserve the order
      cluster.latestGossip.members.toSeq.map(_.address) must be(addresses.toSeq)
    }

    "gossip to random live node" in {
      cluster.latestGossip.members
      cluster.gossip()
      cluster.gossip()
      cluster.gossip()
      cluster.gossip()

      expectMsg(GossipTo(addresses(1)))
      expectMsg(GossipTo(addresses(2)))
      expectMsg(GossipTo(addresses(3)))
      expectMsg(GossipTo(addresses(4)))

      expectNoMsg(1 second)
    }

    "use certain probability for gossiping to deputy node depending on the number of unreachable and live nodes" in {
      cluster._gossipToDeputyProbablity = -1.0 // use real impl
      cluster.gossipToDeputyProbablity(10, 1, 2) must be < (cluster.gossipToDeputyProbablity(9, 1, 2))
      cluster.gossipToDeputyProbablity(10, 1, 2) must be < (cluster.gossipToDeputyProbablity(10, 2, 2))
      cluster.gossipToDeputyProbablity(10, 1, 2) must be < (cluster.gossipToDeputyProbablity(10, 2, 3))
      cluster.gossipToDeputyProbablity(10, 5, 5) must be < (cluster.gossipToDeputyProbablity(10, 9, 5))
      cluster.gossipToDeputyProbablity(0, 10, 0) must be <= (1.0)
      cluster.gossipToDeputyProbablity(1, 10, 1) must be <= (1.0)
      cluster.gossipToDeputyProbablity(10, 0, 0) must be(0.0 plusOrMinus (0.0001))
      cluster.gossipToDeputyProbablity(0, 0, 0) must be(0.0 plusOrMinus (0.0001))
      cluster.gossipToDeputyProbablity(4, 0, 4) must be(1.0 plusOrMinus (0.0001))
      cluster.gossipToDeputyProbablity(3, 7, 4) must be(1.0 plusOrMinus (0.0001))
    }

    "gossip to duputy node" in {
      cluster._gossipToDeputyProbablity = 1.0 // always

      // we have configured 3 deputy nodes (seedNodes)
      cluster.gossip() // 1 is deputy
      cluster.gossip() // 2 is deputy
      cluster.gossip() // 3 is deputy
      cluster.gossip() // 4 is not deputy, and therefore a deputy is also used

      expectMsg(GossipTo(addresses(1)))
      expectMsg(GossipTo(addresses(2)))
      expectMsg(GossipTo(addresses(3)))
      expectMsg(GossipTo(addresses(4)))
      // and the extra gossip to deputy
      expectMsgAnyOf(GossipTo(addresses(1)), GossipTo(addresses(2)), GossipTo(addresses(3)))

      expectNoMsg(1 second)

    }

    "gossip to random deputy node if number of live nodes is less than number of deputy nodes" in {
      cluster._gossipToDeputyProbablity = -1.0 // real impl
      // 0 and 2 still alive
      val dead = Set(addresses(1), addresses(3), addresses(4), addresses(5))
      dead foreach failureDetector.markNodeAsUnavailable

      cluster.reapUnreachableMembers()
      cluster.latestGossip.overview.unreachable.map(_.address) must be(dead)

      for (n ← 1 to 20) {
        cluster.gossip()
        expectMsg(GossipTo(addresses(2))) // the only available
        // and always to one of the 3 deputies
        expectMsgAnyOf(GossipTo(addresses(1)), GossipTo(addresses(2)), GossipTo(addresses(3)))
      }

      expectNoMsg(1 second)

    }
  }
}
