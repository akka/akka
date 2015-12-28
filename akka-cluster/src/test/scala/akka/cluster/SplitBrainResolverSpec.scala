/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import com.typesafe.config.ConfigFactory

object SplitBrainResolverSpec {

  final case class DownCalled(address: Address)

  val addressA = Address("akka.tcp", "sys", "a", 2552)
  val memberA = new Member(UniqueAddress(addressA, 0), 5, Up, Set("role3"))
  val memberB = new Member(UniqueAddress(addressA.copy(host = Some("b")), 0), 4, Up, Set("role1", "role3"))
  val memberC = new Member(UniqueAddress(addressA.copy(host = Some("c")), 0), 3, Up, Set("role2"))
  val memberD = new Member(UniqueAddress(addressA.copy(host = Some("d")), 0), 2, Up, Set("role1", "role2", "role3"))
  val memberE = new Member(UniqueAddress(addressA.copy(host = Some("e")), 0), 1, Up, Set.empty)

  def joining(m: Member): Member = Member(m.uniqueAddress, m.roles)

  class DowningTestActor(
    stableAfter: FiniteDuration,
    strategy: SplitBrainResolver.Strategy,
    probe: ActorRef,
    override val selfUniqueAddress: UniqueAddress)
    extends SplitBrainResolverBase(stableAfter, strategy) {

    // immediate overdue if Duration.Zero is used
    override def newStableDeadline(): Deadline = super.newStableDeadline() - 1.nanos

    var downed = Set.empty[Address]

    override def down(node: Address): Unit = {
      if (leader && !downed(node)) {
        downed += node
        probe ! DownCalled(node)
      } else if (!leader)
        probe ! "down must only be done by leader"
    }

    override def joining: Set[UniqueAddress] = Set.empty

  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SplitBrainResolverSpec extends AkkaSpec {
  import SplitBrainResolverSpec._
  import SplitBrainResolver._

  abstract class StrategySetup {
    def createStrategy(): Strategy

    var side1: Set[Member] = Set.empty
    var side2: Set[Member] = Set.empty
    var side3: Set[Member] = Set.empty

    private def initStrategy(): Strategy = {
      val strategy = createStrategy()
      (side1 ++ side2 ++ side3).foreach(m ⇒ if (m.status != Joining) strategy.add(m))
      strategy
    }

    def assertDowning(members: Set[Member]): Unit = {
      assertDowningSide(side1, members)
      assertDowningSide(side2, members)
      assertDowningSide(side3, members)
    }

    def assertDowningSide(side: Set[Member], members: Set[Member]): Unit = {
      val others = side1 ++ side2 ++ side3 -- side
      (side -- others) should be(side)

      if (side.nonEmpty) {
        val strategy = initStrategy()
        others.foreach(strategy.unreachable += _.uniqueAddress)
        (strategy.decide() match {
          case DownUnreachable ⇒ others
          case DownReachable   ⇒ side
          case DownAll         ⇒ side ++ others
        }) should be(members)
      }
    }

  }

  "StaticQuorum" must {
    class Setup2(size: Int, role: Option[String]) extends StrategySetup {
      override def createStrategy() = new StaticQuorum(size, role)
    }

    "down unreachable when enough reachable nodes" in new Setup2(3, None) {
      side1 = Set(memberA, memberC, memberE)
      side2 = Set(memberB, memberD)
      assertDowning(side2)
    }

    "down unreachable when enough reachable nodes with role" in new Setup2(2, Some("role3")) {
      side1 = Set(memberA, memberB, memberC)
      side2 = Set(memberD, memberE)
      assertDowning(side2)
    }
  }

  "KeepMajority" must {
    class Setup2(role: Option[String]) extends StrategySetup {
      override def createStrategy() = new KeepMajority(role)
    }

    "down minority partition 1" in new Setup2(role = None) {
      side1 = Set(memberA, memberC, memberE)
      side2 = Set(memberB, memberD)
      assertDowning(side2)
    }

    "down minority partition 2" in new Setup2(role = None) {
      side1 = Set(memberA, memberB)
      side2 = Set(memberC, memberD, memberE)
      assertDowning(side1)
    }

    "down self when alone" in new Setup2(role = None) {
      side1 = Set(memberB)
      side2 = Set(memberA, memberC)
      assertDowning(side1)
    }

    "keep half with lowest address when equal size partition" in new Setup2(role = None) {
      side1 = Set(memberA, memberB)
      side2 = Set(memberC, memberD)
      assertDowning(side2)
    }

    "keep node with lowest address in two node cluster" in new Setup2(role = None) {
      side1 = Set(memberA)
      side2 = Set(memberB)
      assertDowning(side2)
    }

    "down minority partition with role" in new Setup2(role = Some("role1")) {
      side1 = Set(memberA, memberB)
      side2 = Set(memberC, memberD, memberE)
      assertDowning(side2)
    }

    "keep half with lowest address with role when equal size partition" in
      new Setup2(role = Some("role2")) {
        side1 = Set(memberA, memberD, memberE)
        side2 = Set(memberB, memberC)
        // memberC is lowest with role2
        assertDowning(side1)
      }

    "down all when no node with role" in new Setup2(role = Some("role3")) {
      side1 = Set(memberC)
      side2 = Set(memberE)
      assertDowning(side1 ++ side2)
    }

    "not count unreachable joining node, but down it" in new Setup2(role = None) {
      side1 = Set(memberB, memberD)
      side2 = Set(joining(memberA), memberC)
      assertDowning(side2)
    }

    "down minority partition and joining node" in new Setup2(role = None) {
      side1 = Set(memberA, joining(memberB))
      side2 = Set(memberC, memberD, memberE)
      assertDowning(side1)
    }

    "down each part when split in 3 too small parts" in new Setup2(role = None) {
      side1 = Set(memberA, memberB)
      side2 = Set(memberC, memberD)
      side3 = Set(memberE)
      assertDowningSide(side1, side1)
      assertDowningSide(side2, side2)
      assertDowningSide(side3, side3)
    }

  }

  "KeepOldest" must {
    class Setup2(downIfAlone: Boolean = true, role: Option[String] = None) extends StrategySetup {
      override def createStrategy() = new KeepOldest(downIfAlone, role)
    }

    "keep partition with oldest" in new Setup2 {
      // E is the oldest
      side1 = Set(memberA, memberE)
      side2 = Set(memberB, memberC, memberD)
      assertDowning(side2)
    }

    "keep partition with oldest with role" in new Setup2(role = Some("role2")) {
      // C and D has role2, D is the oldest
      side1 = Set(memberA, memberE)
      side2 = Set(memberB, memberC, memberD)
      assertDowning(side1)
    }

    "keep partition with oldest unless alone" in new Setup2(downIfAlone = true) {
      side1 = Set(memberE)
      side2 = Set(memberA, memberB, memberC, memberD)
      assertDowning(side1)
    }

    "keep partition with oldest in two nodes cluster" in new Setup2 {
      side1 = Set(memberB)
      side2 = Set(memberA)
      assertDowning(side2)
    }

    "keep one single oldest" in new Setup2 {
      side1 = Set.empty
      side2 = Set(memberA)
      assertDowning(side1)
    }

    "keep oldest even when alone when downIfAlone = false" in new Setup2(downIfAlone = false) {
      side1 = Set(memberE)
      side2 = Set(memberA, memberB, memberC, memberD)
      assertDowning(side2)
    }

  }

  "KeepReferee" must {
    class Setup2(referee: Address, downAllIfLessThanNodes: Int = 1) extends StrategySetup {
      override def createStrategy() = new KeepReferee(referee, downAllIfLessThanNodes)
    }

    "keep partition with referee" in new Setup2(memberE.address) {
      side1 = Set(memberA, memberE)
      side2 = Set(memberB, memberC, memberD)
      assertDowning(side2)
    }

    "down all when referee is removed" in new Setup2(memberE.address) {
      side1 = Set(memberB, memberC, memberD)
      side2 = Set(memberA)
      assertDowning(side1 ++ side2)
    }

    "down all when too few" in new Setup2(memberE.address, downAllIfLessThanNodes = 4) {
      side1 = Set(memberA, memberE)
      side2 = Set(memberB, memberC, memberD)
      assertDowning(side1 ++ side2)
    }
  }

  "Split Brain Resolver" must {

    class SetupKeepMajority(stableAfter: FiniteDuration, selfUniqueAddress: UniqueAddress, role: Option[String])
      extends Setup(stableAfter, new KeepMajority(role), selfUniqueAddress)

    class SetupKeepOldest(stableAfter: FiniteDuration, selfUniqueAddress: UniqueAddress,
                          downIfAlone: Boolean, role: Option[String])
      extends Setup(stableAfter, new KeepOldest(downIfAlone, role), selfUniqueAddress)

    class SetupStaticQuorum(stableAfter: FiniteDuration, selfUniqueAddress: UniqueAddress, size: Int, role: Option[String])
      extends Setup(stableAfter, new StaticQuorum(size, role), selfUniqueAddress)

    abstract class Setup(stableAfter: FiniteDuration, strategy: SplitBrainResolver.Strategy, selfUniqueAddress: UniqueAddress) {
      val a = system.actorOf(Props(classOf[DowningTestActor], stableAfter, strategy, testActor, selfUniqueAddress))

      def memberUp(members: Member*): Unit =
        members.foreach(m ⇒ a ! MemberUp(m))

      def leader(member: Member): Unit =
        a ! LeaderChanged(Some(member.address))

      def unreachable(members: Member*): Unit =
        members.foreach(m ⇒ a ! UnreachableMember(m))

      def reachable(members: Member*): Unit =
        members.foreach(m ⇒ a ! ReachableMember(m))

      def remove(members: Member*): Unit =
        members.foreach(m ⇒ a ! MemberRemoved(m.copy(Removed), previousStatus = Exiting))

      def tick(): Unit = a ! Tick

      def expectDownCalled(members: Member*): Unit =
        receiveN(members.length).toSet should be(members.map(m ⇒ DownCalled(m.address)).toSet)

      def stop(): Unit = {
        system.stop(a)
        expectNoMsg(100.millis)
      }
    }

    "have default down-removal-margin equal to stable-after" in {
      val conf = ConfigFactory.parseString("""
        akka.cluster.split-brain-resolver.active-strategy = keep-majority
        """).withFallback(system.settings.config)
      val settings = new ClusterSettings(conf, "sysname")
      settings.DownRemovalMargin should be(settings.DowningStableAfter)
    }

    "down unreachable when leader" in new SetupKeepMajority(
      Duration.Zero, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberA)
      unreachable(memberB)
      tick()
      expectDownCalled(memberB)
      stop()
    }

    "not down unreachable when not leader" in new SetupKeepMajority(
      Duration.Zero, memberB.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberA)
      unreachable(memberC)
      tick()
      expectNoMsg(500.millis)
      stop()
    }

    "down unreachable when becoming leader" in new SetupKeepMajority(
      stableAfter = Duration.Zero, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberB)
      unreachable(memberC)
      leader(memberA)
      tick()
      expectDownCalled(memberC)
      stop()
    }

    "down unreachable after specified duration" in new SetupKeepMajority(
      stableAfter = 2.seconds, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberA)
      unreachable(memberB)
      expectNoMsg(1.second)
      expectDownCalled(memberB)
      stop()
    }

    "down unreachable when becoming leader inbetween detection and specified duration" in new SetupKeepMajority(
      stableAfter = 2.seconds, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberB)
      unreachable(memberC)
      leader(memberA)
      tick()
      expectNoMsg(1.second)
      expectDownCalled(memberC)
      stop()
    }

    "not down unreachable when loosing leadership inbetween detection and specified duration" in new SetupKeepMajority(
      stableAfter = 1.seconds, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberA)
      unreachable(memberC)
      leader(memberB)
      tick()
      expectNoMsg(1500.millis)
      stop()
    }

    "not down when unreachable become reachable inbetween detection and specified duration" in new SetupKeepMajority(
      stableAfter = 1.seconds, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberA)
      unreachable(memberB)
      reachable(memberB)
      tick()
      expectNoMsg(1500.millis)
      stop()
    }

    "not down when unreachable is removed inbetween detection and specified duration" in new SetupKeepMajority(
      stableAfter = 1.seconds, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberA)
      unreachable(memberB)
      a ! MemberRemoved(memberB.copy(Removed), previousStatus = Exiting)
      tick()
      expectNoMsg(1500.millis)
      stop()
    }

    "not down when unreachable is already Down" in new SetupKeepMajority(
      stableAfter = Duration.Zero, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC)
      leader(memberA)
      unreachable(memberB.copy(Down))
      tick()
      expectNoMsg(1000.millis)
      stop()
    }

    "down minority partition" in new SetupKeepMajority(
      stableAfter = Duration.Zero, memberA.uniqueAddress, role = None) {
      memberUp(memberA, memberB, memberC, memberD, memberE)
      leader(memberA)
      unreachable(memberB, memberD)
      tick()
      expectDownCalled(memberB, memberD)
      stop()
    }

    "keep partition with oldest" in new SetupKeepOldest(
      stableAfter = Duration.Zero, memberA.uniqueAddress, downIfAlone = true, role = None) {
      memberUp(memberA, memberB, memberC, memberD, memberE)
      leader(memberA)
      unreachable(memberB, memberC, memberD)
      tick()
      expectDownCalled(memberB, memberC, memberD)
      stop()
    }

    "log warning if N > static-quorum.size * 2 - 1" in new SetupStaticQuorum(
      stableAfter = Duration.Zero, memberA.uniqueAddress, size = 2, role = None) {
      EventFilter.warning(pattern = "cluster size is \\[4\\].*not add more than \\[3\\]", occurrences = 1).intercept {
        memberUp(memberA, memberB, memberC, memberD)
      }
      leader(memberA)
      unreachable(memberC, memberD)
      tick()
      expectDownCalled(memberC, memberD)
      stop()
    }

  }

}
