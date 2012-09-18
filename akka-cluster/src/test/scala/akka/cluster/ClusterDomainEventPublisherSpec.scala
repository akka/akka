/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import scala.collection.immutable.SortedSet
import scala.concurrent.util.duration._
import akka.actor.Address
import akka.actor.Props
import akka.cluster.MemberStatus._
import akka.cluster.InternalClusterAction._
import akka.cluster.ClusterEvent._
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

object ClusterDomainEventPublisherSpec {
  val config = """
    akka.cluster.auto-join = off
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.netty.port = 0
    """

  case class GossipTo(address: Address)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterDomainEventPublisherSpec extends AkkaSpec(ClusterDomainEventPublisherSpec.config) with ImplicitSender {
  import ClusterDomainEventPublisherSpec._

  val publisher = system.actorOf(Props[ClusterDomainEventPublisher], name = "test-publisher")
  val a1 = Member(Address("akka", "sys", "a", 2552), Up)
  val b1 = Member(Address("akka", "sys", "b", 2552), Up)
  val c1 = Member(Address("akka", "sys", "c", 2552), Joining)
  val c2 = Member(Address("akka", "sys", "c", 2552), Up)
  val d1 = Member(Address("akka", "sys", "a", 2551), Up)

  val g0 = Gossip(members = SortedSet(a1)).seen(a1.address)
  val g1 = Gossip(members = SortedSet(a1, b1, c1)).seen(a1.address).seen(b1.address).seen(c1.address)
  val g2 = Gossip(members = SortedSet(a1, b1, c2)).seen(a1.address)
  val g3 = g2.seen(b1.address).seen(c2.address)
  val g4 = Gossip(members = SortedSet(d1, a1, b1, c2)).seen(a1.address)
  val g5 = Gossip(members = SortedSet(d1, a1, b1, c2)).seen(a1.address).seen(b1.address).seen(c2.address).seen(d1.address)

  "ClusterDomainEventPublisher" must {

    "send snapshot when starting subscription" in {
      publisher ! PublishChanges(g0, g1)
      publisher ! Subscribe(testActor, classOf[ClusterDomainEvent])
      val state = expectMsgType[CurrentClusterState]
      state.members must be(g1.members)
      state.convergence must be(true)
    }

    "publish MemberUp when member status changed to Up" in {
      publisher ! PublishChanges(g1, g2)
      expectMsg(MemberUp(c2))
      expectMsg(ConvergenceChanged(false))
      expectMsgType[SeenChanged]
    }

    "publish convergence true when all seen it" in {
      publisher ! PublishChanges(g2, g3)
      expectMsg(ConvergenceChanged(true))
      expectMsgType[SeenChanged]
    }

    "publish leader changed when new leader and after convergence" in {
      publisher ! PublishChanges(g3, g4)
      expectMsg(MemberUp(d1))
      expectMsg(ConvergenceChanged(false))
      expectMsgType[SeenChanged]

      publisher ! PublishChanges(g4, g5)
      expectMsg(LeaderChanged(Some(d1.address)))
      expectMsg(ConvergenceChanged(true))
      expectMsgType[SeenChanged]

      // convergence both before and after
      publisher ! PublishChanges(g3, g5)
      expectMsg(MemberUp(d1))
      expectMsg(LeaderChanged(Some(d1.address)))
      expectMsgType[SeenChanged]
      expectNoMsg(1 second)

      // not convergence
      publisher ! PublishChanges(g2, g4)
      expectMsg(MemberUp(d1))
      expectNoMsg(1 second)
    }

  }

}
