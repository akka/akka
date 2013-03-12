/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterEach
import akka.actor.Address
import akka.actor.PoisonPill
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
  val aUp = Member(Address("akka.tcp", "sys", "a", 2552), Up)
  val aLeaving = aUp.copy(status = Leaving)
  val aExiting = aUp.copy(status = Exiting)
  val aRemoved = aUp.copy(status = Removed)
  val bUp = Member(Address("akka.tcp", "sys", "b", 2552), Up)
  val bRemoved = bUp.copy(status = Removed)
  val cJoining = Member(Address("akka.tcp", "sys", "c", 2552), Joining)
  val cUp = cJoining.copy(status = Up)
  val cRemoved = cUp.copy(status = Removed)
  val dUp = Member(Address("akka.tcp", "sys", "a", 2551), Up)

  val g0 = Gossip(members = SortedSet(aUp)).seen(aUp.address)
  val g1 = Gossip(members = SortedSet(aUp, bUp, cJoining)).seen(aUp.address).seen(bUp.address).seen(cJoining.address)
  val g2 = Gossip(members = SortedSet(aUp, bUp, cUp)).seen(aUp.address)
  val g3 = g2.seen(bUp.address).seen(cUp.address)
  val g4 = Gossip(members = SortedSet(dUp, aUp, bUp, cUp)).seen(aUp.address)
  val g5 = Gossip(members = SortedSet(dUp, aUp, bUp, cUp)).seen(aUp.address).seen(bUp.address).seen(cUp.address).seen(dUp.address)
  val g6 = Gossip(members = SortedSet(aLeaving, bUp, cUp)).seen(aUp.address)
  val g7 = Gossip(members = SortedSet(aExiting, bUp, cUp)).seen(aUp.address)

  // created in beforeEach
  var memberSubscriber: TestProbe = _

  override def beforeEach(): Unit = {
    memberSubscriber = TestProbe()
    system.eventStream.subscribe(memberSubscriber.ref, classOf[MemberEvent])
    system.eventStream.subscribe(memberSubscriber.ref, classOf[LeaderChanged])

    publisher = system.actorOf(Props[ClusterDomainEventPublisher])
    publisher ! PublishChanges(g0)
    memberSubscriber.expectMsg(MemberUp(aUp))
    memberSubscriber.expectMsg(LeaderChanged(Some(aUp.address)))
  }

  "ClusterDomainEventPublisher" must {

    "publish MemberUp" in {
      publisher ! PublishChanges(g2)
      publisher ! PublishChanges(g3)
      memberSubscriber.expectMsg(MemberUp(bUp))
      memberSubscriber.expectMsg(MemberUp(cUp))
    }

    "publish leader changed" in {
      publisher ! PublishChanges(g4)
      memberSubscriber.expectMsg(MemberUp(dUp))
      memberSubscriber.expectMsg(MemberUp(bUp))
      memberSubscriber.expectMsg(MemberUp(cUp))
      memberSubscriber.expectMsg(LeaderChanged(Some(dUp.address)))
      memberSubscriber.expectNoMsg(1 second)
    }

    "publish leader changed when old leader leaves and is removed" in {
      publisher ! PublishChanges(g3)
      memberSubscriber.expectMsg(MemberUp(bUp))
      memberSubscriber.expectMsg(MemberUp(cUp))
      publisher ! PublishChanges(g6)
      memberSubscriber.expectNoMsg(1 second)
      publisher ! PublishChanges(g7)
      memberSubscriber.expectMsg(MemberExited(aExiting))
      memberSubscriber.expectMsg(LeaderChanged(Some(bUp.address)))
      memberSubscriber.expectNoMsg(1 second)
      // at the removed member a an empty gossip is the last thing
      publisher ! PublishChanges(Gossip.empty)
      memberSubscriber.expectMsg(MemberRemoved(aRemoved))
      memberSubscriber.expectMsg(MemberRemoved(bRemoved))
      memberSubscriber.expectMsg(MemberRemoved(cRemoved))
      memberSubscriber.expectMsg(LeaderChanged(None))
    }

    "not publish leader changed when same leader" in {
      publisher ! PublishChanges(g4)
      memberSubscriber.expectMsg(MemberUp(dUp))
      memberSubscriber.expectMsg(MemberUp(bUp))
      memberSubscriber.expectMsg(MemberUp(cUp))
      memberSubscriber.expectMsg(LeaderChanged(Some(dUp.address)))

      publisher ! PublishChanges(g5)
      memberSubscriber.expectNoMsg(1 second)
    }

    "send CurrentClusterState when subscribe" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[ClusterDomainEvent])
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
      memberSubscriber.expectMsg(MemberUp(bUp))
      memberSubscriber.expectMsg(MemberUp(cUp))
    }

    "publish clean state when PublishStart" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, classOf[ClusterDomainEvent])
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! PublishChanges(g3)
      subscriber.expectMsg(MemberUp(bUp))
      subscriber.expectMsg(MemberUp(cUp))
      subscriber.expectMsgType[SeenChanged]

      publisher ! PublishStart
      subscriber.expectMsgType[CurrentClusterState] must be(CurrentClusterState())
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

    "publish Removed when stopped" in {
      publisher ! PoisonPill
      memberSubscriber.expectMsg(MemberRemoved(aRemoved))
    }

  }
}
