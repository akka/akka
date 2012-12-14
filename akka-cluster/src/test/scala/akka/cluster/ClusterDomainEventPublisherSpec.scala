/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterEach
import akka.actor.Address
import akka.actor.Props
import akka.cluster.MemberStatus._
import akka.cluster.InternalClusterAction._
import akka.cluster.ClusterEvent._
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.ActorRef
import akka.testkit.TestProbe

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
class ClusterDomainEventPublisherSpec extends AkkaSpec(ClusterDomainEventPublisherSpec.config)
  with BeforeAndAfterEach with ImplicitSender {
  import ClusterDomainEventPublisherSpec._

  var publisher: ActorRef = _
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

  override def atStartup(): Unit = {
    system.eventStream.subscribe(testActor, classOf[ClusterDomainEvent])
  }

  override def beforeEach(): Unit = {
    publisher = system.actorOf(Props[ClusterDomainEventPublisher])
    publisher ! PublishChanges(g0)
    expectMsg(MemberUp(a1))
    expectMsg(LeaderChanged(Some(a1.address)))
    expectMsgType[SeenChanged]
  }

  override def afterEach(): Unit = {
    system.stop(publisher)
  }

  "ClusterDomainEventPublisher" must {

    "not publish MemberUp when there is no convergence" in {
      publisher ! PublishChanges(g2)
      expectMsgType[SeenChanged]
    }

    "publish MemberEvents when there is convergence" in {
      publisher ! PublishChanges(g2)
      expectMsgType[SeenChanged]
      publisher ! PublishChanges(g3)
      expectMsg(MemberUp(b1))
      expectMsg(MemberUp(c2))
      expectMsgType[SeenChanged]
    }

    "publish leader changed when new leader after convergence" in {
      publisher ! PublishChanges(g4)
      expectMsgType[SeenChanged]
      expectNoMsg(1 second)

      publisher ! PublishChanges(g5)
      expectMsg(MemberUp(d1))
      expectMsg(MemberUp(b1))
      expectMsg(MemberUp(c2))
      expectMsg(LeaderChanged(Some(d1.address)))
      expectMsgType[SeenChanged]
    }

    "publish leader changed when new leader and convergence both before and after" in {
      // convergence both before and after
      publisher ! PublishChanges(g3)
      expectMsg(MemberUp(b1))
      expectMsg(MemberUp(c2))
      expectMsgType[SeenChanged]
      publisher ! PublishChanges(g5)
      expectMsg(MemberUp(d1))
      expectMsg(LeaderChanged(Some(d1.address)))
      expectMsgType[SeenChanged]
    }

    "not publish leader changed when not convergence" in {
      publisher ! PublishChanges(g4)
      expectMsgType[SeenChanged]
      expectNoMsg(1 second)
    }

    "not publish leader changed when changed convergence but still same leader" in {
      publisher ! PublishChanges(g5)
      expectMsg(MemberUp(d1))
      expectMsg(MemberUp(b1))
      expectMsg(MemberUp(c2))
      expectMsg(LeaderChanged(Some(d1.address)))
      expectMsgType[SeenChanged]

      publisher ! PublishChanges(g4)
      expectMsgType[SeenChanged]

      publisher ! PublishChanges(g5)
      expectMsgType[SeenChanged]
    }

    "send CurrentClusterState when subscribe" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[ClusterDomainEvent])
      subscriber.expectMsgType[CurrentClusterState]
      // but only to the new subscriber
      expectNoMsg(1 second)
    }

    "support unsubscribe" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[ClusterDomainEvent])
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! Unsubscribe(subscriber.ref, Some(classOf[ClusterDomainEvent]))
      publisher ! PublishChanges(g3)
      subscriber.expectNoMsg(1 second)
      // but testActor is still subscriber
      expectMsg(MemberUp(b1))
      expectMsg(MemberUp(c2))
      expectMsgType[SeenChanged]
    }
  }
}
