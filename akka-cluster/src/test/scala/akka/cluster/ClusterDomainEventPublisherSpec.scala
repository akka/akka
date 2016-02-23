/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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

object ClusterDomainEventPublisherSpec {
  val config = """
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.netty.tcp.port = 0
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterDomainEventPublisherSpec extends AkkaSpec(ClusterDomainEventPublisherSpec.config)
  with BeforeAndAfterEach with ImplicitSender {

  var publisher: ActorRef = _
  val aUp = TestMember(Address("akka.tcp", "sys", "a", 2552), Up)
  val aLeaving = aUp.copy(status = Leaving)
  val aExiting = aLeaving.copy(status = Exiting)
  val aRemoved = aExiting.copy(status = Removed)
  val bExiting = TestMember(Address("akka.tcp", "sys", "b", 2552), Exiting)
  val bRemoved = bExiting.copy(status = Removed)
  val cJoining = TestMember(Address("akka.tcp", "sys", "c", 2552), Joining, Set("GRP"))
  val cUp = cJoining.copy(status = Up)
  val cRemoved = cUp.copy(status = Removed)
  val a51Up = TestMember(Address("akka.tcp", "sys", "a", 2551), Up)
  val dUp = TestMember(Address("akka.tcp", "sys", "d", 2552), Up, Set("GRP"))

  val g0 = Gossip(members = SortedSet(aUp)).seen(aUp.uniqueAddress)
  val g1 = Gossip(members = SortedSet(aUp, cJoining)).seen(aUp.uniqueAddress).seen(cJoining.uniqueAddress)
  val g2 = Gossip(members = SortedSet(aUp, bExiting, cUp)).seen(aUp.uniqueAddress)
  val g3 = g2.seen(bExiting.uniqueAddress).seen(cUp.uniqueAddress)
  val g4 = Gossip(members = SortedSet(a51Up, aUp, bExiting, cUp)).seen(aUp.uniqueAddress)
  val g5 = Gossip(members = SortedSet(a51Up, aUp, bExiting, cUp)).seen(aUp.uniqueAddress).seen(bExiting.uniqueAddress).seen(cUp.uniqueAddress).seen(a51Up.uniqueAddress)
  val g6 = Gossip(members = SortedSet(aLeaving, bExiting, cUp)).seen(aUp.uniqueAddress)
  val g7 = Gossip(members = SortedSet(aExiting, bExiting, cUp)).seen(aUp.uniqueAddress)
  val g8 = Gossip(members = SortedSet(aUp, bExiting, cUp, dUp), overview = GossipOverview(reachability =
    Reachability.empty.unreachable(aUp.uniqueAddress, dUp.uniqueAddress))).seen(aUp.uniqueAddress)

  // created in beforeEach
  var memberSubscriber: TestProbe = _

  override def beforeEach(): Unit = {
    memberSubscriber = TestProbe()
    system.eventStream.subscribe(memberSubscriber.ref, classOf[MemberEvent])
    system.eventStream.subscribe(memberSubscriber.ref, classOf[LeaderChanged])
    system.eventStream.subscribe(memberSubscriber.ref, ClusterShuttingDown.getClass)

    publisher = system.actorOf(Props[ClusterDomainEventPublisher])
    publisher ! PublishChanges(g0)
    memberSubscriber.expectMsg(MemberUp(aUp))
    memberSubscriber.expectMsg(LeaderChanged(Some(aUp.address)))
  }

  "ClusterDomainEventPublisher" must {

    "publish MemberJoined" in {
      publisher ! PublishChanges(g1)
      memberSubscriber.expectMsg(MemberJoined(cJoining))
    }

    "publish MemberUp" in {
      publisher ! PublishChanges(g2)
      publisher ! PublishChanges(g3)
      memberSubscriber.expectMsg(MemberExited(bExiting))
      memberSubscriber.expectMsg(MemberUp(cUp))
    }

    "publish leader changed" in {
      publisher ! PublishChanges(g4)
      memberSubscriber.expectMsg(MemberUp(a51Up))
      memberSubscriber.expectMsg(MemberExited(bExiting))
      memberSubscriber.expectMsg(MemberUp(cUp))
      memberSubscriber.expectMsg(LeaderChanged(Some(a51Up.address)))
      memberSubscriber.expectNoMsg(500 millis)
    }

    "publish leader changed when old leader leaves and is removed" in {
      publisher ! PublishChanges(g3)
      memberSubscriber.expectMsg(MemberExited(bExiting))
      memberSubscriber.expectMsg(MemberUp(cUp))
      publisher ! PublishChanges(g6)
      memberSubscriber.expectMsg(MemberLeft(aLeaving))
      publisher ! PublishChanges(g7)
      memberSubscriber.expectMsg(MemberExited(aExiting))
      memberSubscriber.expectMsg(LeaderChanged(Some(cUp.address)))
      memberSubscriber.expectNoMsg(500 millis)
      // at the removed member a an empty gossip is the last thing
      publisher ! PublishChanges(Gossip.empty)
      memberSubscriber.expectMsg(MemberRemoved(aRemoved, Exiting))
      memberSubscriber.expectMsg(MemberRemoved(bRemoved, Exiting))
      memberSubscriber.expectMsg(MemberRemoved(cRemoved, Up))
      memberSubscriber.expectMsg(LeaderChanged(None))
    }

    "not publish leader changed when same leader" in {
      publisher ! PublishChanges(g4)
      memberSubscriber.expectMsg(MemberUp(a51Up))
      memberSubscriber.expectMsg(MemberExited(bExiting))
      memberSubscriber.expectMsg(MemberUp(cUp))
      memberSubscriber.expectMsg(LeaderChanged(Some(a51Up.address)))

      publisher ! PublishChanges(g5)
      memberSubscriber.expectNoMsg(500 millis)
    }

    "publish role leader changed" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, InitialStateAsSnapshot, Set(classOf[RoleLeaderChanged]))
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! PublishChanges(Gossip(members = SortedSet(cJoining, dUp)))
      subscriber.expectMsg(RoleLeaderChanged("GRP", Some(dUp.address)))
      publisher ! PublishChanges(Gossip(members = SortedSet(cUp, dUp)))
      subscriber.expectMsg(RoleLeaderChanged("GRP", Some(cUp.address)))
    }

    "send CurrentClusterState when subscribe" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, InitialStateAsSnapshot, Set(classOf[ClusterDomainEvent]))
      subscriber.expectMsgType[CurrentClusterState]
      // but only to the new subscriber
      memberSubscriber.expectNoMsg(500 millis)

    }

    "send events corresponding to current state when subscribe" in {
      val subscriber = TestProbe()
      publisher ! PublishChanges(g8)
      publisher ! Subscribe(subscriber.ref, InitialStateAsEvents, Set(classOf[MemberEvent], classOf[ReachabilityEvent]))
      subscriber.receiveN(4).toSet should be(Set(MemberUp(aUp), MemberUp(cUp), MemberUp(dUp), MemberExited(bExiting)))
      subscriber.expectMsg(UnreachableMember(dUp))
      subscriber.expectNoMsg(500 millis)
    }

    "support unsubscribe" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, InitialStateAsSnapshot, Set(classOf[MemberEvent]))
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! Unsubscribe(subscriber.ref, Some(classOf[MemberEvent]))
      publisher ! PublishChanges(g3)
      subscriber.expectNoMsg(500 millis)
      // but memberSubscriber is still subscriber
      memberSubscriber.expectMsg(MemberExited(bExiting))
      memberSubscriber.expectMsg(MemberUp(cUp))
    }

    "publish SeenChanged" in {
      val subscriber = TestProbe()
      publisher ! Subscribe(subscriber.ref, InitialStateAsSnapshot, Set(classOf[SeenChanged]))
      subscriber.expectMsgType[CurrentClusterState]
      publisher ! PublishChanges(g2)
      subscriber.expectMsgType[SeenChanged]
      subscriber.expectNoMsg(500 millis)
      publisher ! PublishChanges(g3)
      subscriber.expectMsgType[SeenChanged]
      subscriber.expectNoMsg(500 millis)
    }

    "publish ClusterShuttingDown and Removed when stopped" in {
      publisher ! PoisonPill
      memberSubscriber.expectMsg(ClusterShuttingDown)
      memberSubscriber.expectMsg(MemberRemoved(aRemoved, Up))
    }

  }
}
