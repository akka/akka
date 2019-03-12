/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration._
import akka.actor.Address
import akka.actor.Scheduler
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.MemberStatus._
import akka.cluster.ClusterEvent._
import akka.remote.RARP
import akka.testkit.AkkaSpec
import akka.testkit.TimingTest

object AutoDownSpec {
  final case class DownCalled(address: Address)

  class AutoDownTestActor(memberA: Member, autoDownUnreachableAfter: FiniteDuration, probe: ActorRef)
      extends AutoDownBase(autoDownUnreachableAfter) {

    override def selfAddress = memberA.address
    override def scheduler: Scheduler = context.system.scheduler

    override def down(node: Address): Unit = {
      if (leader)
        probe ! DownCalled(node)
      else
        probe ! "down must only be done by leader"
    }

  }

}

class AutoDownSpec extends AkkaSpec("akka.actor.provider=remote") {
  import AutoDownSpec._

  val protocol =
    if (RARP(system).provider.remoteSettings.Artery.Enabled) "akka"
    else "akka.tcp"

  val memberA = TestMember(Address(protocol, "sys", "a", 2552), Up)
  val memberB = TestMember(Address(protocol, "sys", "b", 2552), Up)
  val memberC = TestMember(Address(protocol, "sys", "c", 2552), Up)

  def autoDownActor(autoDownUnreachableAfter: FiniteDuration): ActorRef =
    system.actorOf(Props(classOf[AutoDownTestActor], memberA, autoDownUnreachableAfter, testActor))

  "AutoDown" must {

    "down unreachable when leader" in {
      val a = autoDownActor(Duration.Zero)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberB)
      expectMsg(DownCalled(memberB.address))
    }

    "not down unreachable when not leader" in {
      val a = autoDownActor(Duration.Zero)
      a ! LeaderChanged(Some(memberB.address))
      a ! UnreachableMember(memberC)
      expectNoMsg(1.second)
    }

    "down unreachable when becoming leader" in {
      val a = autoDownActor(Duration.Zero)
      a ! LeaderChanged(Some(memberB.address))
      a ! UnreachableMember(memberC)
      a ! LeaderChanged(Some(memberA.address))
      expectMsg(DownCalled(memberC.address))
    }

    "down unreachable after specified duration" in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberB)
      expectNoMsg(1.second)
      expectMsg(DownCalled(memberB.address))
    }

    "down unreachable when becoming leader in-between detection and specified duration" in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberB.address))
      a ! UnreachableMember(memberC)
      a ! LeaderChanged(Some(memberA.address))
      expectNoMsg(1.second)
      expectMsg(DownCalled(memberC.address))
    }

    "not down unreachable when losing leadership in-between detection and specified duration" taggedAs TimingTest in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberC)
      a ! LeaderChanged(Some(memberB.address))
      expectNoMsg(3.second)
    }

    "not down when unreachable become reachable in-between detection and specified duration" taggedAs TimingTest in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberB)
      a ! ReachableMember(memberB)
      expectNoMsg(3.second)
    }

    "not down when unreachable is removed in-between detection and specified duration" taggedAs TimingTest in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberB)
      a ! MemberRemoved(memberB.copy(Removed), previousStatus = Exiting)
      expectNoMsg(3.second)
    }

    "not down when unreachable is already Down" in {
      val a = autoDownActor(Duration.Zero)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberB.copy(Down))
      expectNoMsg(1.second)
    }

  }
}
