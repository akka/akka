/*
 * Copyright (C) 2024-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.immutable
import scala.collection.immutable.SortedSet
import scala.util.Random

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
import akka.cluster.sharding.ShardRegion.ShardId
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

  private def strategy() =
    // we don't really "start" it as we fake the cluster access
    new SliceRangeShardAllocationStrategy(10, 0.1) {
      override protected def clusterState: ClusterEvent.CurrentClusterState =
        CurrentClusterState(SortedSet(memberA, memberB, memberC))
      override protected def selfMember: Member = memberA
    }

  private def createAllocationStrategy(members: IndexedSeq[Member]) = {
    new SliceRangeShardAllocationStrategy(10, 0.1) {
      override protected def clusterState: CurrentClusterState =
        CurrentClusterState(SortedSet(members: _*))

      override protected def selfMember: Member = members.head
    }
  }

  private def sliceRanges(numberOfRanges: Int): immutable.IndexedSeq[Range] = {
    val numberOfSlices = 1024
    val rangeSize = numberOfSlices / numberOfRanges
    require(
      numberOfRanges * rangeSize == numberOfSlices,
      s"numberOfRanges [$numberOfRanges] must be a whole number divisor of numberOfSlices [$numberOfSlices].")
    (0 until numberOfRanges).map { i =>
      (i * rangeSize until i * rangeSize + rangeSize)
    }.toVector
  }

  private def numberOfSliceRangesPerRegion(
      numberOfRanges: Int,
      shardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Map[ActorRef, Int] = {
    val ranges = sliceRanges(numberOfRanges)
    shardAllocations.map {
      case (region, shards) =>
        region -> shards.map(s => ranges.find(_.contains(s.toInt)).get).toSet.size
    }
  }

  class Setup(initialNumberOfMembers: Int) {

    def rndSeed: Long = System.currentTimeMillis()

    val rnd = new Random(rndSeed)

    private var members: Vector[Member] =
      (1 to initialNumberOfMembers).map(n => newUpMember(s"127.0.0.$n", upNbr = n)).toVector

    private var regions: Vector[ActorRef] =
      members.map(m => newFakeRegion(s"region${m.upNumber}", m))

    private var allocations: Map[ActorRef, Vector[ShardId]] =
      regions.map(_ -> Vector.empty[String]).toMap

    private var oldAllocations: Map[ActorRef, Vector[ShardId]] =
      Map.empty

    private val allSlices: Vector[Int] =
      (0 to 1023).toVector

    private var strategy = createAllocationStrategy(members)

    def numberOfMembers: Int = members.size

    def allocateAll(): Map[ActorRef, Vector[ShardId]] =
      allocate(allSlices)

    def allocate(slices: Vector[Int], shuffle: Boolean = true): Map[ActorRef, Vector[ShardId]] = {
      val shuffledSlices = if (shuffle) rnd.shuffle(slices) else slices
      shuffledSlices.foreach { slice =>
        val region = strategy.allocateShard(regionA, slice.toString, allocations).futureValue
        allocations = allocations.updated(region, allocations(region) :+ slice.toString)
      }
      if (oldAllocations.isEmpty)
        oldAllocations = allocations
      allocations
    }

    def allocateMissingSlices(atMost: Int): Map[ActorRef, Vector[ShardId]] = {
      val allocatedSlices = allocations.valuesIterator.flatten.map(_.toInt).toSeq
      val missingSlices = allSlices.diff(allocatedSlices)
      val allocateSlices = rnd.shuffle(missingSlices).take(atMost)
      allocate(allocateSlices, shuffle = false)
    }

    def rebalance(): Vector[Int] = {
      val rebalancedShards = strategy.rebalance(allocations, Set.empty).futureValue
      allocations = allocations.map { case (region, shards) => region -> shards.filterNot(rebalancedShards.contains) }
      rebalancedShards.map(_.toInt).toVector
    }

    def removeMember(i: Int): Vector[Int] = {
      val member = members(i)
      members = members.filterNot(_ == member)
      val region = regions(i)
      regions = regions.filterNot(_ == region)
      val removedShards = allocations(region)
      allocations = allocations - region
      strategy = createAllocationStrategy(members)
      removedShards.map(_.toInt)
    }

    def addMember(): Unit = {
      val n = members.last.upNumber + 1
      val newMember = newUpMember(s"127.0.0.$n", upNbr = n)
      members :+= newMember
      val newRegion = newFakeRegion(s"region${newMember.upNumber}", newMember)
      regions :+= newRegion
      allocations = allocations.updated(newRegion, Vector.empty)
      strategy = createAllocationStrategy(members)
    }

    private def sort(shards: Vector[ShardId]): Vector[Int] =
      shards.map(_.toInt).sorted

    def printAllocations(verbose: Boolean = true): Unit = {
      val rangesPerRegion8 = numberOfSliceRangesPerRegion(8, allocations)
      val rangesPerRegion16 = numberOfSliceRangesPerRegion(16, allocations)
      val hasOldAllocations = oldAllocations.nonEmpty && (oldAllocations ne allocations)
      val oldRangesPerRegion8 = numberOfSliceRangesPerRegion(8, oldAllocations)
      val oldRangesPerRegion16 = numberOfSliceRangesPerRegion(16, oldAllocations)

      val optimalSize = 1024.0 / allocations.size
      var varianceSum = 0.0

      var totalSame = 0
      //also add old allocations to new allocations, but with empty shards
      val allocationsWithEmptyOldEntries =
        if (hasOldAllocations) {
          allocations ++ oldAllocations.iterator.collect {
            case (region, _) if !allocations.contains(region) =>
              region -> Vector.empty[String]
          }
        } else
          allocations
      allocationsWithEmptyOldEntries.toIndexedSeq
        .sortBy { case (_, shards) => if (shards.isEmpty) Int.MaxValue else shards.minBy(_.toInt).toInt }
        .foreach {
          case (region, shards) =>
            val deltaFromOptimal = if (allocations.contains(region)) shards.size - optimalSize else 0.0
            varianceSum += deltaFromOptimal * deltaFromOptimal

            if (hasOldAllocations) {
              val oldShards = oldAllocations.getOrElse(region, Vector.empty)
              val added = shards.diff(oldShards)
              val removed = oldShards.diff(shards)
              val same = shards.intersect(oldShards)
              totalSame += same.size

              println(
                f"${region.path.name}: ${oldShards.size}->${shards.size}, +${added.size}, -${removed.size}, Δ$deltaFromOptimal%1.1f")
              if (verbose) {
                println(s"old ${region.path.name}: ${oldShards.size}, ${oldRangesPerRegion8.getOrElse(region, 0)} of 8 ranges, " +
                s"${oldRangesPerRegion16.getOrElse(region, 0)} of 16 ranges \n    (${sort(oldShards).mkString(", ")})")
                println(
                  s"new ${region.path.name}: ${shards.size}, ${rangesPerRegion8.getOrElse(region, 0)} of 8 ranges, " +
                  s"${rangesPerRegion16.getOrElse(region, 0)} of 16 ranges \n    (${sort(shards).mkString(", ")})")
              }
            } else if (verbose) {
              println(
                f"${region.path.name}: ${shards.size}, Δ$deltaFromOptimal%1.1f, ${rangesPerRegion8.getOrElse(region, 0)} of 8 ranges, " +
                s"${rangesPerRegion16.getOrElse(region, 0)} of 16 ranges \n    (${sort(shards).mkString(", ")})")
            } else {
              println(s"${region.path.name}: ${shards.size}")
            }
        }

      val stdDeviation = math.sqrt(varianceSum / allocations.size)
      println(f"Standard deviation from optimal size $stdDeviation%1.1f")

      if (hasOldAllocations) {
        println(s"$totalSame shards kept at same region")
        println(
          s"old total of ${oldRangesPerRegion8.valuesIterator.sum} connections from ${oldAllocations.size} nodes " +
          "to 8 backend ranges")
        println(
          s"old total of ${oldRangesPerRegion16.valuesIterator.sum} connections from ${oldAllocations.size} nodes " +
          "to 16 backend ranges")
      }
      println(
        s"total of ${rangesPerRegion8.valuesIterator.sum} connections from ${allocations.size} nodes " +
        "to 8 backend ranges")
      println(
        s"total of ${rangesPerRegion16.valuesIterator.sum} connections from ${allocations.size} nodes " +
        "to 16 backend ranges")
    }

  }

  "SliceRangeShardAllocationStrategy" must {
    "allocate to regions" ignore { // FIXME
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
        val setup = new Setup(N)

        info(s"rnd seed ${setup.rndSeed}")

        println(s"# N=$N")
        setup.allocateAll()
        setup.printAllocations()

        println("\n")
        println("\n")
      }
    }

    "try member change impact" in new Setup(50) {
      info(s"rnd seed $rndSeed")

      allocateAll()

      // remove one and add one member, pick one in the middle
      val removedSlices = removeMember(numberOfMembers / 2)
      addMember()
      allocate(removedSlices)
      printAllocations()
    }

    "try rebalance impact" in new Setup(50) {
      info(s"rnd seed $rndSeed")

      allocateAll()

      addMember()
      (1 to 100).foreach { n =>
        val rebalancedSlices = rebalance()
        println(s"rebalance #$n: ${rebalancedSlices.sorted}")
        allocate(rebalancedSlices)
      }
      printAllocations(verbose = false)
    }

    "try simulation" in new Setup(100) {
      info(s"rnd seed $rndSeed")

      allocateAll()

      (1 to 100).foreach { _ =>
        rnd.nextInt(21) match {
          case 0 =>
            addMember()
          case 1 =>
            removeMember(rnd.nextInt(numberOfMembers))
          case 2 =>
            removeMember(rnd.nextInt(numberOfMembers))
            addMember()
          case n if 3 <= n && n <= 12 =>
            allocateMissingSlices(rnd.nextInt(10))
          case n if 13 <= n && n <= 20 =>
            val rebalancedSlices = rebalance()
            allocate(rebalancedSlices)
        }
      }

      allocateMissingSlices(1024)

      (1 to 100).foreach { n =>
        val rebalancedSlices = rebalance()
        println(s"rebalance #$n: ${rebalancedSlices.sorted}")
        allocate(rebalancedSlices)
      }

      printAllocations(verbose = false)

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
