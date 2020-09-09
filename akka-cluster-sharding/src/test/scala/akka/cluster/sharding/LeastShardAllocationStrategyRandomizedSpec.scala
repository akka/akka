/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.Random

import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.sharding.ShardRegion.ShardId
import akka.testkit.AkkaSpec

class LeastShardAllocationStrategyRandomizedSpec extends AkkaSpec("akka.loglevel = INFO") {

  def createAllocations(countPerRegion: Map[ActorRef, Int]): Map[ActorRef, immutable.IndexedSeq[ShardId]] = {
    countPerRegion.map {
      case (region, count) =>
        region -> (1 to count).map(n => ("00" + n.toString).takeRight(3)).map(n => s"${region.path.name}-$n").toVector
    }
  }

  private val strategyWithoutLimits = new LeastShardAllocationStrategy(absoluteLimit = 100000, relativeLimit = 1.0)

  private val rndSeed = System.currentTimeMillis()
  private val rnd = new Random(rndSeed)
  info(s"Random seed: $rndSeed")

  private var iteration = 1
  private val iterationsPerTest = 10

  private def afterRebalance(
      allocationStrategy: LeastShardAllocationStrategy,
      allocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalance: Set[ShardId]): Map[ActorRef, immutable.IndexedSeq[ShardId]] = {
    val allocationsAfterRemoval = allocations.map {
      case (region, shards) => region -> shards.filterNot(rebalance)
    }

    rebalance.foldLeft(allocationsAfterRemoval) {
      case (acc, shard) =>
        val region = allocationStrategy.allocateShard(testActor, shard, acc).value.get.get
        acc.updated(region, acc(region) :+ shard)
    }
  }

  private def countShardsPerRegion(newAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Vector[Int] = {
    newAllocations.valuesIterator.map(_.size).toVector
  }

  private def countShards(allocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Int = {
    countShardsPerRegion(allocations).sum
  }

  private def testRebalance(
      allocationStrategy: LeastShardAllocationStrategy,
      maxRegions: Int,
      maxShardsPerRegion: Int,
      expectedMaxSteps: Int): Unit = {
    (1 to iterationsPerTest).foreach { _ =>
      iteration += 1
      val numberOfRegions = rnd.nextInt(maxRegions) + 1
      val regions = (1 to numberOfRegions).map(n => system.actorOf(Props.empty, s"$iteration-R$n"))
      val countPerRegion = regions.map { region =>
        region -> rnd.nextInt(maxShardsPerRegion)
      }.toMap
      val allocations = createAllocations(countPerRegion)
      withClue(s"test $allocationStrategy [${countShardsPerRegion(allocations).mkString(",")}]: ") {
        testRebalance(allocationStrategy, allocations, Vector(allocations), expectedMaxSteps)
      }
      regions.foreach(system.stop)
    }
  }

  @tailrec private def testRebalance(
      allocationStrategy: LeastShardAllocationStrategy,
      allocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      steps: Vector[Map[ActorRef, immutable.IndexedSeq[ShardId]]],
      maxSteps: Int): Unit = {
    val round = steps.size
    val rebalanceResult = allocationStrategy.rebalance(allocations, Set.empty).value.get.get
    val newAllocations = afterRebalance(allocationStrategy, allocations, rebalanceResult)

    countShards(newAllocations) should ===(countShards(allocations))
    val min = countShardsPerRegion(newAllocations).min
    val max = countShardsPerRegion(newAllocations).max
    val diff = max - min
    val newSteps = steps :+ newAllocations
    if (diff <= 1) {
      if (round >= 3 && maxSteps <= 10) {
        // Should be very rare (I have not seen it)
        system.log.info(
          s"rebalance solved in round $round, [${newSteps.map(step => countShardsPerRegion(step).mkString(",")).mkString(" => ")}]")
      }
      ()
    } else if (round == maxSteps) {
      fail(
        s"Couldn't solve rebalance in $round rounds, [${newSteps.map(step => countShardsPerRegion(step).mkString(",")).mkString(" => ")}]")
    } else {
      testRebalance(allocationStrategy, newAllocations, newSteps, maxSteps)
    }
  }

  "LeastShardAllocationStrategy with random scenario" must {

    "rebalance shards with max 5 regions / 5 shards" in {
      testRebalance(strategyWithoutLimits, maxRegions = 5, maxShardsPerRegion = 5, expectedMaxSteps = 2)
    }

    "rebalance shards with max 5 regions / 100 shards" in {
      testRebalance(strategyWithoutLimits, maxRegions = 5, maxShardsPerRegion = 100, expectedMaxSteps = 2)
    }

    "rebalance shards with max 20 regions / 5 shards" in {
      testRebalance(strategyWithoutLimits, maxRegions = 20, maxShardsPerRegion = 5, expectedMaxSteps = 2)
    }

    "rebalance shards with max 20 regions / 20 shards" in {
      testRebalance(strategyWithoutLimits, maxRegions = 20, maxShardsPerRegion = 20, expectedMaxSteps = 2)
    }

    "rebalance shards with max 20 regions / 200 shards" in {
      testRebalance(strategyWithoutLimits, maxRegions = 20, maxShardsPerRegion = 200, expectedMaxSteps = 5)
    }

    "rebalance shards with max 100 regions / 100 shards" in {
      testRebalance(strategyWithoutLimits, maxRegions = 100, maxShardsPerRegion = 100, expectedMaxSteps = 5)
    }

    "rebalance shards with max 100 regions / 1000 shards" in {
      testRebalance(strategyWithoutLimits, maxRegions = 100, maxShardsPerRegion = 1000, expectedMaxSteps = 5)
    }

    "rebalance shards with max 20 regions / 20 shards and limits" in {
      val absoluteLimit = 3 + rnd.nextInt(7) + 3
      val relativeLimit = 0.05 + (rnd.nextDouble() * 0.95)

      val strategy = new LeastShardAllocationStrategy(absoluteLimit, relativeLimit)
      testRebalance(strategy, maxRegions = 20, maxShardsPerRegion = 20, expectedMaxSteps = 20)
    }

  }

}
