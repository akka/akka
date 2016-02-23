/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration._
import akka.actor.Address
import akka.actor.Scheduler
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.MemberStatus._
import akka.cluster.ClusterEvent._
import akka.testkit.AkkaSpec

object AutoDownSpec {
  final case class DownCalled(address: Address)

  val memberA = TestMember(Address("akka.tcp", "sys", "a", 2552), Up)
  val memberB = TestMember(Address("akka.tcp", "sys", "b", 2552), Up)
  val memberC = TestMember(Address("akka.tcp", "sys", "c", 2552), Up)

  class AutoDownTestActor(
    autoDownUnreachableAfter: FiniteDuration,
    probe: ActorRef)
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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AutoDownSpec extends AkkaSpec {
  import AutoDownSpec._

  def autoDownActor(autoDownUnreachableAfter: FiniteDuration): ActorRef =
    system.actorOf(Props(classOf[AutoDownTestActor], autoDownUnreachableAfter, testActor))

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

    "down unreachable when becoming leader inbetween detection and specified duration" in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberB.address))
      a ! UnreachableMember(memberC)
      a ! LeaderChanged(Some(memberA.address))
      expectNoMsg(1.second)
      expectMsg(DownCalled(memberC.address))
    }

    "not down unreachable when losing leadership inbetween detection and specified duration" in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberC)
      a ! LeaderChanged(Some(memberB.address))
      expectNoMsg(3.second)
    }

    "not down when unreachable become reachable inbetween detection and specified duration" in {
      val a = autoDownActor(2.seconds)
      a ! LeaderChanged(Some(memberA.address))
      a ! UnreachableMember(memberB)
      a ! ReachableMember(memberB)
      expectNoMsg(3.second)
    }

    "not down when unreachable is removed inbetween detection and specified duration" in {
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
