/*
 * Copyright (C) 2024-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.immutable.SortedSet

import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.ActorRefProvider
import akka.actor.Address
import akka.actor.MinimalActorRef
import akka.actor.RootActorPath
import akka.cluster.ClusterEvent
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterSettings
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.UniqueAddress
import akka.testkit.AkkaSpec
import akka.util.Version

object SliceRangeShardAllocationStrategySpec {

  final class DummyActorRef(val path: ActorPath) extends MinimalActorRef {
    override def provider: ActorRefProvider = ???
  }

  def newUpMember(host: String, upNbr: Int, port: Int = 252525, version: Version = Version("1.0.0")) =
    Member(
      UniqueAddress(Address("akka", "myapp", host, port), 1L),
      Set(ClusterSettings.DcRolePrefix + ClusterSettings.DefaultDataCenter),
      version).copy(MemberStatus.Up).copyUp(upNbr)

  def newFakeRegion(idForDebug: String, member: Member): ActorRef =
    new DummyActorRef(RootActorPath(member.address) / "system" / "fake" / idForDebug)
}

class SliceRangeShardAllocationStrategySpec extends AkkaSpec {
  import SliceRangeShardAllocationStrategySpec._

  val memberA = newUpMember("127.0.0.1", upNbr = 1)
  val memberB = newUpMember("127.0.0.2", upNbr = 2)
  val memberC = newUpMember("127.0.0.3", upNbr = 3)
  val memberD = newUpMember("127.0.0.4", upNbr = 4)

  val regionA = newFakeRegion("regionA", memberA)
  val regionB = newFakeRegion("regionB", memberB)
  val regionC = newFakeRegion("regionC", memberC)
  val regionD = newFakeRegion("regionD", memberD)

  private val emptyAllocationsABC: Map[ActorRef, Vector[String]] =
    Map(regionA -> Vector.empty, regionB -> Vector.empty, regionC -> Vector.empty)

  private def strategy(rebalanceLimit: Int = 0) =
    // we don't really "start" it as we fake the cluster access
    new SliceRangeShardAllocationStrategy(rebalanceLimit) {
      override protected def clusterState: ClusterEvent.CurrentClusterState =
        CurrentClusterState(SortedSet(memberA, memberB, memberC))
      override protected def selfMember: Member = memberA
    }

  "SliceRangeShardAllocationStrategy" must {
    "allocate to regions" in {
      val allocationStrategy = strategy()
      val allocations = emptyAllocationsABC
      // 3 regions => slice ranges 0-340, 341-681, 682-1021 and the remainder are allocated round-robin (slice % 3)
      allocationStrategy.allocateShard(regionA, "0", allocations).futureValue should ===(regionA)
      allocationStrategy.allocateShard(regionA, "100", allocations).futureValue should ===(regionA)
      allocationStrategy.allocateShard(regionA, "340", allocations).futureValue should ===(regionA)
      allocationStrategy.allocateShard(regionA, "341", allocations).futureValue should ===(regionB)
      allocationStrategy.allocateShard(regionA, "681", allocations).futureValue should ===(regionB)
      allocationStrategy.allocateShard(regionA, "682", allocations).futureValue should ===(regionC)
      allocationStrategy.allocateShard(regionA, "1021", allocations).futureValue should ===(regionC)
      allocationStrategy.allocateShard(regionA, "1022", allocations).futureValue should ===(regionC)
      allocationStrategy.allocateShard(regionA, "1023", allocations).futureValue should ===(regionA)
    }

    // FIXME just temporary playground
    "try distributions" in {
      (1 to 100).foreach { N =>
        println(s"# N=$N") // FIXME
        val members = (1 to N).map(n => newUpMember(s"127.0.0.$n", upNbr = n))
        val regions = members.map(m => newFakeRegion(s"region${m.upNumber}", m))
        val strategy = new SliceRangeShardAllocationStrategy(0) {
          override protected def clusterState: ClusterEvent.CurrentClusterState =
            CurrentClusterState(SortedSet(members: _*))

          override protected def selfMember: Member = members.head
        }
        var allocations = regions.map(_ -> Vector.empty[String]).toMap

        (0 to 1023).foreach { slice =>
          val region = strategy.allocateShard(regionA, slice.toString, allocations).futureValue
          allocations = allocations.updated(region, allocations(region) :+ slice.toString)
        }

        allocations.toIndexedSeq.sortBy(_._2.min).foreach {
          case (region, shards) =>
            println(s"# ${region.path.name}: ${shards.size} (${shards.mkString(", ")})") // FIXME
        }
        println()
        println()
      }
    }

//    "allocate to mostly same regions when node is removed" in {
//      val allocationStrategy = strategy()
//      val allocations = emptyAllocationsABC
//      allocationStrategy.allocateShard(regionA, "0", allocations).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "1", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "2", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "3", allocations).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "10", allocations).futureValue should ===(regionA)
//      allocationStrategy.allocateShard(regionA, "14", allocations).futureValue should ===(regionA)
//
//      val allocations2 = allocations - regionC
//      allocationStrategy.allocateShard(regionA, "0", allocations2).futureValue should ===(regionA)
//      allocationStrategy.allocateShard(regionA, "1", allocations2).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "2", allocations2).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "3", allocations2).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "10", allocations2).futureValue should ===(regionA)
//      allocationStrategy.allocateShard(regionA, "14", allocations2).futureValue should ===(regionA)
//    }
//
//    "allocate to mostly same regions when node is added" in {
//      val allocationStrategy = strategy()
//      val allocations = emptyAllocationsABC
//      allocationStrategy.allocateShard(regionA, "0", allocations).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "1", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "2", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "3", allocations).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "10", allocations).futureValue should ===(regionA)
//      allocationStrategy.allocateShard(regionA, "14", allocations).futureValue should ===(regionA)
//
//      val allocations2 = allocations.updated(regionD, Vector.empty)
//      allocationStrategy.allocateShard(regionA, "0", allocations2).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "1", allocations2).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "2", allocations2).futureValue should ===(regionD)
//      allocationStrategy.allocateShard(regionA, "3", allocations2).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "10", allocations2).futureValue should ===(regionA)
//      allocationStrategy.allocateShard(regionA, "14", allocations2).futureValue should ===(regionA)
//    }
//
//    "not rebalance when nodes not changed" in {
//      val allocationStrategy = strategy()
//      val allocations = emptyAllocationsABC
//      allocationStrategy.allocateShard(regionA, "0", allocations).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "1", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "2", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "10", allocations).futureValue should ===(regionA)
//
//      val allocations2 = Map(regionA -> Vector("10"), regionB -> Vector("1", "2"), regionC -> Vector("0"))
//      allocationStrategy.rebalance(allocations2, Set.empty).futureValue should ===(Set.empty[String])
//    }
//
//    "rebalance when node is added" in {
//      val allocationStrategy = strategy()
//      val allocations = emptyAllocationsABC
//      allocationStrategy.allocateShard(regionA, "0", allocations).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "1", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "2", allocations).futureValue should ===(regionB)
//      allocationStrategy.allocateShard(regionA, "3", allocations).futureValue should ===(regionC)
//      allocationStrategy.allocateShard(regionA, "10", allocations).futureValue should ===(regionA)
//      allocationStrategy.allocateShard(regionA, "14", allocations).futureValue should ===(regionA)
//
//      val allocations2 = Map(
//        regionA -> Vector("10", "14"),
//        regionB -> Vector("1", "2"),
//        regionC -> Vector("0", "3"),
//        regionD -> Vector.empty)
//      allocationStrategy.rebalance(allocations2, Set.empty).futureValue should ===(Set("2"))
//
//      val allocations3 = Map(
//        regionB -> Vector("2", "1"),
//        regionA -> Vector("10", "14"),
//        regionD -> Vector.empty,
//        regionC -> Vector("3", "0"))
//      allocationStrategy.rebalance(allocations3, Set.empty).futureValue should ===(Set("2"))
//    }
//
//    "not rebalance more than limit" in {
//      val allocationStrategy = strategy(rebalanceLimit = 2)
//      val allocations = Map(
//        regionA -> Vector("0", "1", "2", "3", "10", "14"),
//        regionB -> Vector.empty,
//        regionC -> Vector.empty,
//        regionD -> Vector.empty)
//      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("0", "1"))
//
//      val allocations2 = Map(
//        regionA -> Vector("2", "3", "10", "14"),
//        regionB -> Vector("1"),
//        regionC -> Vector("0"),
//        regionD -> Vector.empty)
//      allocationStrategy.rebalance(allocations2, Set.empty).futureValue should ===(Set("2", "3"))
//
//      val allocations3 =
//        Map(regionA -> Vector("10", "14"), regionB -> Vector("1"), regionC -> Vector("0", "3"), regionD -> Vector("2"))
//      allocationStrategy.rebalance(allocations3, Set.empty).futureValue should ===(Set.empty[String])
//    }
//
//    "not rebalance those that are in progress" in {
//      val allocationStrategy = strategy(rebalanceLimit = 2)
//      val allocations = Map(
//        regionA -> Vector("0", "1", "2", "3", "10", "14"),
//        regionB -> Vector.empty,
//        regionC -> Vector.empty,
//        regionD -> Vector.empty)
//      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("0", "1"))
//      allocationStrategy.rebalance(allocations, Set("0", "1")).futureValue should ===(Set("2", "3"))
//      // 10 and 14 are already at right place
//      allocationStrategy.rebalance(allocations, Set("0", "1", "2", "3")).futureValue should ===(Set.empty[String])
//    }
//
//    "not rebalance when rolling update in progress" in {
//      val allocationStrategy =
//        new SliceRangeShardAllocationStrategy(rebalanceLimit = 0) {
//
//          val member1 = newUpMember("127.0.0.1", upNbr = 1, version = Version("1.0.0"))
//          val member2 = newUpMember("127.0.0.2", upNbr = 2, version = Version("1.0.1"))
//          val member3 = newUpMember("127.0.0.3", upNbr = 3, version = Version("1.0.0"))
//
//          // multiple versions to simulate rolling update in progress
//          override protected def clusterState: CurrentClusterState =
//            CurrentClusterState(SortedSet(member1, member2, member3))
//
//          override protected def selfMember: Member = member1
//        }
//      val allocations = Map(regionA -> Vector("0", "1", "2", "3", "10", "14"), regionB -> Vector.empty)
//      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])
//    }
//
//    "not rebalance when regions are unreachable" in {
//      val allocationStrategy =
//        new SliceRangeShardAllocationStrategy(rebalanceLimit = 0) {
//
//          override protected def clusterState: CurrentClusterState =
//            CurrentClusterState(SortedSet(memberA, memberB, memberC), unreachable = Set(memberB))
//          override protected def selfMember: Member = memberB
//        }
//      val allocations =
//        Map(regionA -> Vector("0", "1", "2", "3", "10", "14"), regionB -> Vector.empty, regionC -> Vector.empty)
//      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])
//    }
//    "not rebalance when members are joining dc" in {
//      val allocationStrategy =
//        new SliceRangeShardAllocationStrategy(rebalanceLimit = 0) {
//
//          val member1 = newUpMember("127.0.0.1", upNbr = 1)
//          val member2 =
//            Member(
//              UniqueAddress(Address("akka", "myapp", "127.0.0.2", 252525), 1L),
//              Set(ClusterSettings.DcRolePrefix + ClusterSettings.DefaultDataCenter),
//              member1.appVersion).copyUp(2)
//          val member3 = newUpMember("127.0.0.3", upNbr = 3)
//
//          override protected def clusterState: CurrentClusterState =
//            CurrentClusterState(SortedSet(member1, member2, member3), unreachable = Set.empty)
//          override protected def selfMember: Member = member2
//        }
//      val allocations =
//        Map(regionA -> Vector("0", "1", "2", "3", "10", "14"), regionB -> Vector.empty, regionC -> Vector.empty)
//      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])
//
//    }

  }
}
