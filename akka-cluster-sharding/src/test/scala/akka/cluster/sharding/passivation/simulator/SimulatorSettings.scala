/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.passivation.simulator

import akka.japi.Util.immutableSeq
import com.typesafe.config.Config

import java.util.Locale
import scala.collection.immutable

final case class SimulatorSettings(runs: immutable.Seq[SimulatorSettings.RunSettings], printDetailedStats: Boolean)

object SimulatorSettings {
  def apply(testConfig: Config): SimulatorSettings = {
    val simulatorConfig = testConfig.getConfig("akka.cluster.sharding.passivation.simulator")
    val runDefaults = simulatorConfig.getConfig("run-defaults")
    val runs = immutableSeq(simulatorConfig.getConfigList("runs")).map { runConfig =>
      RunSettings(simulatorConfig, runConfig.withFallback(runDefaults))
    }
    val printDetailedStats = simulatorConfig.getBoolean("print-detailed-stats")
    SimulatorSettings(runs, printDetailedStats)
  }

  final case class RunSettings(
      name: String,
      shards: Int,
      regions: Int,
      strategy: StrategySettings,
      pattern: PatternSettings)

  object RunSettings {
    def apply(simulatorConfig: Config, runConfig: Config): RunSettings = {
      val name = runConfig.getString("name")
      val shards = runConfig.getInt("shards")
      val regions = runConfig.getInt("regions")
      val strategy = StrategySettings(simulatorConfig, runConfig.getString("strategy"))
      val pattern = PatternSettings(simulatorConfig, runConfig.getString("pattern"))
      RunSettings(name, shards, regions, strategy, pattern)
    }
  }

  sealed trait StrategySettings

  object StrategySettings {
    final case class LeastRecentlyUsed(perRegionLimit: Int) extends StrategySettings

    def apply(simulatorConfig: Config, strategy: String): StrategySettings = {
      val config = simulatorConfig.getConfig(strategy).withFallback(simulatorConfig.getConfig("strategy-defaults"))
      lowerCase(config.getString("strategy")) match {
        case "least-recently-used" => LeastRecentlyUsed(config.getInt("least-recently-used.per-region-limit"))
        case _                     => sys.error(s"Unknown strategy for [$strategy]")
      }
    }
  }

  sealed trait PatternSettings

  object PatternSettings {
    final case class Synthetic(generator: Synthetic.Generator, events: Int) extends PatternSettings

    object Synthetic {
      sealed trait Generator
      final case class Sequence(start: Long) extends Generator
      final case class Uniform(min: Long, max: Long) extends Generator
      final case class Exponential(mean: Double) extends Generator
      final case class Hotspot(min: Long, max: Long, hot: Double, rate: Double) extends Generator
      final case class Zipfian(min: Long, max: Long, constant: Double, scrambled: Boolean) extends Generator

      def apply(patternConfig: Config): Synthetic = {
        val config = patternConfig.getConfig("synthetic")
        val generator = lowerCase(config.getString("generator")) match {
          case "sequence" =>
            val start = config.getLong("sequence.start")
            Sequence(start)
          case "uniform" =>
            val min = config.getLong("uniform.min")
            val max = config.getLong("uniform.max")
            Uniform(min, max)
          case "exponential" =>
            val mean = config.getDouble("exponential.mean")
            Exponential(mean)
          case "hotspot" =>
            val min = config.getLong("hotspot.min")
            val max = config.getLong("hotspot.max")
            val hot = config.getDouble("hotspot.hot")
            val rate = config.getDouble("hotspot.rate")
            Hotspot(min, max, hot, rate)
          case "zipfian" =>
            val min = config.getLong("zipfian.min")
            val max = config.getLong("zipfian.max")
            val constant = config.getDouble("zipfian.constant")
            val scrambled = config.getBoolean("zipfian.scrambled")
            Zipfian(min, max, constant, scrambled)
        }
        Synthetic(generator, config.getInt("events"))
      }
    }

    final case class Trace(path: String, format: String) extends PatternSettings

    object Trace {
      def apply(patternConfig: Config): Trace = {
        val config = patternConfig.getConfig("trace")
        val path = config.getString("path")
        val format = lowerCase(config.getString("format"))
        Trace(path, format)
      }
    }

    def apply(simulatorConfig: Config, pattern: String): PatternSettings = {
      val config = simulatorConfig.getConfig(pattern).withFallback(simulatorConfig.getConfig("pattern-defaults"))
      lowerCase(config.getString("pattern")) match {
        case "synthetic" => Synthetic(config)
        case "trace"     => Trace(config)
        case _           => sys.error(s"Unknown pattern for [$pattern]")
      }
    }
  }

  private def lowerCase(s: String): String = s.toLowerCase(Locale.ROOT)
}
