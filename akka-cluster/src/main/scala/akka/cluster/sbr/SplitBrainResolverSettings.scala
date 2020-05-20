/**
 * Copyright (C) 2015-2020 Lightbend Inc.  <https://www.lightbend.com>
 */
package akka.cluster.sbr

import java.util.Locale
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import akka.ConfigurationException
import akka.util.Helpers
import akka.util.Helpers.Requiring
import com.typesafe.config.Config

/**
 * Internal API
 */
private[sbr] object SplitBrainResolverSettings {
  final val KeepMajorityName = "keep-majority"
  final val LeaseMajorityName = "lease-majority"
  final val StaticQuorumName = "static-quorum"
  final val KeepOldestName = "keep-oldest"
  final val DownAllName = "down-all"

  def allStrategyNames =
    Set(KeepMajorityName, LeaseMajorityName, StaticQuorumName, KeepOldestName, DownAllName)
}

/**
 * Internal API
 */
private[sbr] class SplitBrainResolverSettings(config: Config) {

  import SplitBrainResolverSettings._

  private val cc = config.getConfig("akka.cluster.split-brain-resolver")

  val DowningStableAfter: FiniteDuration = {
    val key = "stable-after"
    FiniteDuration(cc.getDuration(key).toMillis, TimeUnit.MILLISECONDS).requiring(_ >= Duration.Zero, key + " >= 0s")
  }

  val DowningStrategy: String =
    cc.getString("active-strategy").toLowerCase(Locale.ROOT) match {
      case "off" | "" =>
        throw new ConfigurationException(
          s"Split brain downing provider enabled but no strategy selected. " +
          "Please choose a strategy and set using akka.cluster.split-brain-resolver.active-strategy")
      case strategyName if allStrategyNames(strategyName) => strategyName
      case unknown                                        => throw new ConfigurationException(s"Unknown downing strategy: [$unknown]")
    }

  val DownAllWhenUnstable: FiniteDuration = {
    val key = "down-all-when-unstable"
    Helpers.toRootLowerCase(cc.getString("down-all-when-unstable")) match {
      case "on" =>
        // based on stable-after
        DowningStableAfter * 3 / 4
      case "off" =>
        // disabled
        Duration.Zero
      case _ =>
        FiniteDuration(cc.getDuration(key).toMillis, TimeUnit.MILLISECONDS)
          .requiring(_ > Duration.Zero, key + " > 0s, or 'off' to disable")
    }
  }

  // the individual sub-configs below should only be called when the strategy has been selected

  def keepMajorityRole = role(strategyConfig(KeepMajorityName))

  def staticQuorumSettings = {
    val c = strategyConfig(StaticQuorumName)
    val size = c
      .getInt("quorum-size")
      .requiring(_ >= 1, s"akka.cluster.split-brain-resolver.$StaticQuorumName.quorum-size must be >= 1")
    StaticQuorumSettings(size, role(c))
  }

  def keepOldestSettings = {
    val c = strategyConfig(KeepOldestName)
    val downIfAlone = c.getBoolean("down-if-alone")
    KeepOldestSettings(downIfAlone, role(c))
  }

  def leaseMajoritySettings = {
    val c = strategyConfig(LeaseMajorityName)

    val leaseImplementation = c.getString("lease-implementation")
    require(
      leaseImplementation != "",
      s"akka.cluster.split-brain-resolver.$LeaseMajorityName.lease-implementation must be defined")

    val acquireLeaseDelayForMinority =
      FiniteDuration(c.getDuration("acquire-lease-delay-for-minority").toMillis, TimeUnit.MILLISECONDS)

    LeaseMajoritySettings(leaseImplementation, acquireLeaseDelayForMinority, role(c))
  }

  private def strategyConfig(strategyName: String): Config = cc.getConfig(strategyName)

  private def role(c: Config): Option[String] = c.getString("role") match {
    case "" => None
    case r  => Some(r)
  }

}

/**
 * Internal API
 */
private[sbr] final case class StaticQuorumSettings(size: Int, role: Option[String])

/**
 * Internal API
 */
private[sbr] final case class KeepOldestSettings(downIfAlone: Boolean, role: Option[String])

/**
 * Internal API
 */
private[sbr] final case class LeaseMajoritySettings(
    leaseImplementation: String,
    acquireLeaseDelayForMinority: FiniteDuration,
    role: Option[String])
