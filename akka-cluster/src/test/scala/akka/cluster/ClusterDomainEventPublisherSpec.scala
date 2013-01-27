/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterDomainEventPublisherSpec extends AkkaSpec
  with BeforeAndAfterEach with ImplicitSender {

  var publisher: ActorRef = _
  val a1 = Member(Address("akka.tcp", "sys", "a", 2552), Up)
  val b1 = Member(Address("akka.tcp", "sys", "b", 2552), Up)
  val c1 = Member(Address("akka.tcp", "sys", "c", 2552), Joining)
  val c2 = Member(Address("akka.tcp", "sys", "c", 2552), Up)
  val d1 = Member(Address("akka.tcp", "sys", "a", 2551), Up)

  val g0 = Gossip(members = SortedSet(a1)).seen(a1.address)
  val g1 = Gossip(members = SortedSet(a1, b1, c1)).seen(a1.address).seen(b1.address).seen(c1.address)
  val g2 = Gossip(members = SortedSet(a1, b1, c2)).seen(a1.address)
  val g3 = g2.seen(b1.address).seen(c2.address)
  val g4 = Gossip(members = SortedSet(d1, a1, b1, c2)).seen(a1.address)
  val g5 = Gossip(members = SortedSet(d1, a1, b1, c2)).seen(a1.address).seen(b1.address).seen(c2.address).seen(d1.address)

  // created in beforeEach
  var memberSubscriber: TestProbe = _

  override def beforeEach(): Unit = {
    memberSubscriber = TestProbe()
    system.eventStream.subscribe(memberSubscriber.ref, classOf[MemberEvent])
    system.eventStream.subscribe(memberSubscriber.ref, classOf[LeaderChanged])

    publisher = system.actorOf(Props[ClusterDomainEventPublisher])
    publisher ! PublishChanges(g0)
    memberSubscriber.expectMsg(MemberUp(a1))
    memberSubscriber.expectMsg(LeaderChanged(Some(a1.address)))
  }

  override def afterEach(): Unit = {
    system.stop(publisher)
  }

  "ClusterDomainEventPublisher" must {

    "not publish MemberUp when there is no convergence" in {
      publisher ! PublishChanges(g2)
    }

    "publish MemberEvents when there is convergence" in {
      publisher ! PublishChanges(g2)
      publisher ! PublishChanges(g3)
      memberSubscriber.expectMsg(MemberUp(b1))
      memberSubscriber.expectMsg(MemberUp(c2))
    }

    "publish leader changed when new leader after convergence" in {
      publisher ! PublishChanges(g4)
      memberSubscriber.expectNoMsg(1 second)

      publisher ! PublishChanges(g5)
      memberSubscriber.expectMsg(MemberUp(d1))
      memberSubscriber.expectMsg(MemberUp(b1))
      memberSubscriber.expectMsg(MemberUp(c2))
      memberSubscriber.expectMsg(LeaderChanged(Some(d1.address)))
    }

    "publish leader changed when new leader and convergence both before and after" in {
      // convergence both before and after
      publisher ! PublishChanges(g3)
      memberSubscriber.expectMsg(MemberUp(b1))
      memberSubscriber.expectMsg(MemberUp(c2))
      publisher ! PublishChanges(g5)
      memberSubscriber.expectMsg(MemberUp(d1))
      memberSubscriber.expectMsg(LeaderChanged(Some(d1.address)))
    }

    "not publish leader changed when not convergence" in {
      publisher ! PublishChanges(g4)
      memberSubscriber.expectNoMsg(1 second)
    }

    "not publish leader changed when changed convergence but still same leader" in {
      publisher ! PublishChanges(g5)
      memberSubscriber.expectMsg(MemberUp(d1))
      memberSubscriber.expectMsg(MemberUp(b1))
      memberSubscriber.expectMsg(MemberUp(c2))
      memberSubscriber.expectMsg(LeaderChanged(Some(d1.address)))

      publisher ! PublishChanges(g4)
      memberSubscriber.expectNoMsg(1 second)

      publisher ! PublishChanges(g5)
      memberSubscriber.expectNoMsg(1 second)
    }

    "send CurrentClusterState when subscribe" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[ClusterDomainEvent])
      subscriber.expectMsgType[InstantClusterState]
      subscriber.expectMsgType[CurrentClusterState]
      // but only to the new subscriber
      memberSubscriber.expectNoMsg(1 second)
    }

    "support unsubscribe" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[MemberEvent])
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! Unsubscribe(subscriber.ref, Some(classOf[MemberEvent]))
      publisher ! PublishChanges(g3)
      subscriber.expectNoMsg(1 second)
      // but memberSubscriber is still subscriber
      memberSubscriber.expectMsg(MemberUp(b1))
      memberSubscriber.expectMsg(MemberUp(c2))
    }

    "publish clean state when PublishStart" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[ClusterDomainEvent])
      subscriber.expectMsgType[InstantClusterState]
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! PublishChanges(g3)
      subscriber.expectMsg(InstantMemberUp(b1))
      subscriber.expectMsg(InstantMemberUp(c2))
      subscriber.expectMsg(MemberUp(b1))
      subscriber.expectMsg(MemberUp(c2))
      subscriber.expectMsgType[SeenChanged]

      publisher ! PublishStart
      subscriber.expectMsgType[CurrentClusterState] must be(CurrentClusterState())
    }

    "publish immediately when subscribing to InstantMemberEvent" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[InstantMemberEvent])
      subscriber.expectMsgType[InstantClusterState]
      publisher ! PublishChanges(g2)
      subscriber.expectMsg(InstantMemberUp(b1))
      subscriber.expectMsg(InstantMemberUp(c2))
      subscriber.expectNoMsg(1 second)
      publisher ! PublishChanges(g3)
      subscriber.expectNoMsg(1 second)
    }

    "publish SeenChanged" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[SeenChanged])
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! PublishChanges(g2)
      subscriber.expectMsgType[SeenChanged]
      subscriber.expectNoMsg(1 second)
      publisher ! PublishChanges(g3)
      subscriber.expectMsgType[SeenChanged]
      subscriber.expectNoMsg(1 second)
    }

  }
}
