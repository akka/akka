/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.passivation.simulator

import akka.NotUsed
import akka.actor.ActorSystem
import akka.cluster.sharding.internal.{ EntityPassivationStrategy, LeastRecentlyUsedEntityPassivationStrategy }
import akka.stream.scaladsl.{ Flow, Source }
import com.typesafe.config.ConfigFactory

import scala.collection.{ immutable, mutable }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
 * Simulator for testing the efficiency of passivation strategies.
 * For access pattern, can read cache replacement policy traces or generate synthetic events.
 */
object Simulator {
  def main(args: Array[String]): Unit = {
    val configName = args.headOption
    println(s"Running passivation simulator with [${configName.getOrElse("default application")}] config")
    val config = configName.fold(ConfigFactory.load)(ConfigFactory.load)
    run(SimulatorSettings(config))
  }

  final case class Results(name: String, stats: ShardingStats)

  def run(settings: SimulatorSettings): Unit = {
    val simulations = settings.runs.map(Simulation.apply)
    implicit val system: ActorSystem = ActorSystem("simulator")
    implicit val ec: ExecutionContext = system.dispatcher
    Source(simulations)
      .runFoldAsync(Seq.empty[Results]) { (results, simulation) =>
        println(s"Running [${simulation.name}] ...")
        simulate(simulation).map(stats => results :+ Results(simulation.name, stats))
      }
      .onComplete {
        case Success(allResults) =>
          val summary = allResults.map { results =>
            if (settings.printDetailedStats) {
              println(results.name)
              PrintData(DataTable(results.stats))
              println()
            }
            results.name +: DataTable.row(results.stats.totals)
          }
          PrintData(DataTable(DataTable.Headers.RunStats, summary))
          system.terminate()
        case Failure(exception) =>
          println(s"Failed to run simulations: $exception")
          system.terminate()
      }
  }

  final case class Simulation(
      name: String,
      numberOfShards: Int,
      numberOfRegions: Int,
      accessPattern: AccessPattern,
      createStrategy: () => EntityPassivationStrategy)

  object Simulation {
    def apply(runSettings: SimulatorSettings.RunSettings): Simulation =
      Simulation(
        name = runSettings.name,
        numberOfShards = runSettings.shards,
        numberOfRegions = runSettings.regions,
        accessPattern = accessPattern(runSettings),
        createStrategy = strategyCreator(runSettings))

    def accessPattern(runSettings: SimulatorSettings.RunSettings): AccessPattern = runSettings.pattern match {
      case SimulatorSettings.PatternSettings.Synthetic(generator, events) =>
        generator match {
          case SimulatorSettings.PatternSettings.Synthetic.Sequence(start) =>
            new SyntheticGenerator.Sequence(start, events)
          case SimulatorSettings.PatternSettings.Synthetic.Uniform(min, max) =>
            new SyntheticGenerator.Uniform(min, max, events)
          case SimulatorSettings.PatternSettings.Synthetic.Exponential(mean) =>
            new SyntheticGenerator.Exponential(mean, events)
          case SimulatorSettings.PatternSettings.Synthetic.Hotspot(min, max, hot, rate) =>
            new SyntheticGenerator.Hotspot(min, max, hot, rate, events)
          case SimulatorSettings.PatternSettings.Synthetic.Zipfian(min, max, constant, scrambled) =>
            if (scrambled) new SyntheticGenerator.ScrambledZipfian(min, max, constant, events)
            else new SyntheticGenerator.Zipfian(min, max, constant, events)
        }
      case SimulatorSettings.PatternSettings.Trace(path, format) =>
        format match {
          case "arc"    => new TraceFileReader.Arc(path)
          case "simple" => new TraceFileReader.Simple(path)
          case _        => sys.error(s"Unknown trace file format [$format]")
        }
    }

    def strategyCreator(runSettings: SimulatorSettings.RunSettings): () => EntityPassivationStrategy =
      runSettings.strategy match {
        case SimulatorSettings.StrategySettings.LeastRecentlyUsed(perRegionLimit) =>
          () => new LeastRecentlyUsedEntityPassivationStrategy(perRegionLimit)
      }
  }

  object Id {
    def hashed(id: String, n: Int): String =
      padded(math.abs(id.hashCode % n), n - 1)

    def padded(id: Int, max: Int): String = {
      val maxDigits = math.floor(math.log10(max)).toInt + 1
      s"%0${maxDigits}d".format(id)
    }
  }

  type RegionId = String
  type ShardId = String
  type EntityId = String

  final case class Access(regionId: RegionId, shardId: ShardId, entityId: EntityId)

  sealed trait Event
  final case class Accessed(regionId: RegionId, shardId: ShardId, entityId: EntityId) extends Event
  final case class Activated(regionId: RegionId, shardId: ShardId, entityId: EntityId) extends Event
  final case class Passivated(regionId: RegionId, shardId: ShardId, entityIds: immutable.Seq[EntityId]) extends Event

  def simulate(simulation: Simulation)(implicit system: ActorSystem): Future[ShardingStats] =
    simulation.accessPattern.entityIds
      .via(ShardAllocation(simulation.numberOfShards, simulation.numberOfRegions))
      .via(ShardingState(simulation.createStrategy))
      .runWith(SimulatorStats())

  object ShardAllocation {
    def apply(numberOfShards: Int, numberOfRegions: Int): Flow[EntityId, Access, NotUsed] =
      Flow[EntityId].statefulMapConcat(() => {
        val allocation = new ShardAllocation(numberOfShards, numberOfRegions)
        entityId => List(allocation.access(entityId))
      })
  }

  final class ShardAllocation(numberOfShards: Int, numberOfRegions: Int) {
    private var allocationMap: Map[RegionId, Set[ShardId]] = (1 to numberOfRegions).map { id =>
      Id.padded(id, numberOfRegions) -> Set.empty[ShardId]
    }.toMap

    def access(entityId: EntityId): Access = {
      val shardId = extractShardId(entityId)
      val regionId = currentRegion(shardId).getOrElse(allocateShard(shardId))
      Access(regionId, shardId, entityId)
    }

    // simulate default shard id extractor
    private def extractShardId(entityId: EntityId): ShardId =
      Id.hashed(entityId, numberOfShards)

    private def currentRegion(shardId: ShardId): Option[RegionId] =
      allocationMap.collectFirst { case (regionId, allocated) if allocated.contains(shardId) => regionId }

    // simulate default least shard allocation
    private def allocateShard(shardId: ShardId): RegionId = {
      val regionId = allocationMap.toSeq.sortBy(_._1).minBy(_._2)(Ordering.by(_.size))._1
      allocationMap = allocationMap.updated(regionId, allocationMap(regionId) + shardId)
      regionId
    }
  }

  object ShardingState {
    def apply(createStrategy: () => EntityPassivationStrategy): Flow[Access, Event, NotUsed] =
      Flow[Access].statefulMapConcat(() => {
        val state = new ShardingState(createStrategy)
        access => state.process(access)
      })
  }

  final class ShardingState(createStrategy: () => EntityPassivationStrategy) {
    private val active = mutable.Map.empty[RegionId, mutable.Map[ShardId, ShardState]]

    def process(access: Access): Seq[Event] = {
      val regionId = access.regionId
      val shardId = access.shardId
      val region = active.getOrElseUpdate(regionId, mutable.Map.empty)
      val alreadyActive = region.contains(shardId)
      val shard = region.getOrElseUpdate(shardId, new ShardState(regionId, shardId, createStrategy()))
      val passivated = if (!alreadyActive) region.values.toSeq.flatMap(_.updated(region.size)) else Nil
      passivated ++ shard.accessed(access.entityId)
    }
  }

  final class ShardState(regionId: RegionId, shardId: ShardId, strategy: EntityPassivationStrategy) {
    private val activeEntities = mutable.Set.empty[EntityId]

    def updated(activeShards: Int): immutable.Seq[Event] = {
      val passivateEntities = strategy.shardsUpdated(activeShards)
      passivateEntities.foreach(activeEntities.remove)
      if (passivateEntities.isEmpty) Nil
      else List(Passivated(regionId, shardId, passivateEntities))
    }

    def accessed(entityId: EntityId): immutable.Seq[Event] = {
      val changes = if (activeEntities.contains(entityId)) {
        strategy.entityTouched(entityId)
        Nil
      } else {
        activeEntities += entityId
        val passivateEntities = strategy.entityCreated(entityId)
        passivateEntities.foreach(activeEntities.remove)
        val passivated =
          if (passivateEntities.isEmpty) Nil
          else List(Passivated(regionId, shardId, passivateEntities))
        passivated :+ Activated(regionId, shardId, entityId)
      }
      changes :+ Accessed(regionId, shardId, entityId)
    }
  }
}
