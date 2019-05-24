/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import com.typesafe.config.Config

import scala.concurrent.duration._
import akka.util.Timeout

import scala.collection.immutable
import akka.util.Helpers.{ toRootLowerCase, ConfigOps, Requiring }
import akka.japi.Util._
import akka.actor.Props
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.ConfigurationException
import akka.remote.artery.ArterySettings

final class RemoteSettings(val config: Config) {
  import config._
  import akka.util.ccompat.JavaConverters._

  val Artery = ArterySettings(getConfig("akka.remote.artery"))

  val WarnAboutDirectUse: Boolean = getBoolean("akka.remote.warn-about-direct-use")

  val LogReceive: Boolean = getBoolean("akka.remote.classic.log-received-messages")

  val LogSend: Boolean = getBoolean("akka.remote.classic.log-sent-messages")

  val LogFrameSizeExceeding: Option[Int] = {
    if (config.getString("akka.remote.classic.log-frame-size-exceeding").toLowerCase == "off") None
    else Some(getBytes("akka.remote.classic.log-frame-size-exceeding").toInt)
  }

  val UntrustedMode: Boolean = getBoolean("akka.remote.classic.untrusted-mode")

  val TrustedSelectionPaths: Set[String] =
    immutableSeq(getStringList("akka.remote.classic.trusted-selection-paths")).toSet

  val RemoteLifecycleEventsLogLevel: LogLevel = toRootLowerCase(
    getString("akka.remote.classic.log-remote-lifecycle-events")) match {
    case "on" => Logging.DebugLevel
    case other =>
      Logging.levelFor(other) match {
        case Some(level) => level
        case None =>
          throw new ConfigurationException("Logging level must be one of (on, off, debug, info, warning, error)")
      }
  }

  val Dispatcher: String = getString("akka.remote.classic.use-dispatcher")

  def configureDispatcher(props: Props): Props = if (Dispatcher.isEmpty) props else props.withDispatcher(Dispatcher)

  val ShutdownTimeout: Timeout = {
    Timeout(config.getMillisDuration("akka.remote.classic.shutdown-timeout"))
  }.requiring(_.duration > Duration.Zero, "shutdown-timeout must be > 0")

  val FlushWait: FiniteDuration = {
    config.getMillisDuration("akka.remote.classic.flush-wait-on-shutdown")
  }.requiring(_ > Duration.Zero, "flush-wait-on-shutdown must be > 0")

  val StartupTimeout: Timeout = {
    Timeout(config.getMillisDuration("akka.remote.classic.startup-timeout"))
  }.requiring(_.duration > Duration.Zero, "startup-timeout must be > 0")

  val RetryGateClosedFor: FiniteDuration = {
    config.getMillisDuration("akka.remote.classic.retry-gate-closed-for")
  }.requiring(_ >= Duration.Zero, "retry-gate-closed-for must be >= 0")

  val UsePassiveConnections: Boolean = getBoolean("akka.remote.classic.use-passive-connections")

  val BackoffPeriod: FiniteDuration = {
    config.getMillisDuration("akka.remote.classic.backoff-interval")
  }.requiring(_ > Duration.Zero, "backoff-interval must be > 0")

  val LogBufferSizeExceeding: Int = {
    val key = "akka.remote.classic.log-buffer-size-exceeding"
    config.getString(key).toLowerCase match {
      case "off" | "false" => Int.MaxValue
      case _               => config.getInt(key)
    }
  }

  val SysMsgAckTimeout: FiniteDuration = {
    config.getMillisDuration("akka.remote.classic.system-message-ack-piggyback-timeout")
  }.requiring(_ > Duration.Zero, "system-message-ack-piggyback-timeout must be > 0")

  val SysResendTimeout: FiniteDuration = {
    config.getMillisDuration("akka.remote.classic.resend-interval")
  }.requiring(_ > Duration.Zero, "resend-interval must be > 0")

  val SysResendLimit: Int = {
    config.getInt("akka.remote.classic.resend-limit")
  }.requiring(_ > 0, "resend-limit must be > 0")

  val SysMsgBufferSize: Int = {
    getInt("akka.remote.classic.system-message-buffer-size")
  }.requiring(_ > 0, "system-message-buffer-size must be > 0")

  val InitialSysMsgDeliveryTimeout: FiniteDuration = {
    config.getMillisDuration("akka.remote.classic.initial-system-message-delivery-timeout")
  }.requiring(_ > Duration.Zero, "initial-system-message-delivery-timeout must be > 0")

  val QuarantineSilentSystemTimeout: FiniteDuration = {
    val key = "akka.remote.classic.quarantine-after-silence"
    config.getString(key).toLowerCase match {
      case "off" | "false" => Duration.Zero
      case _ =>
        config.getMillisDuration(key).requiring(_ > Duration.Zero, "quarantine-after-silence must be > 0")
    }
  }

  val QuarantineDuration: FiniteDuration = {
    config
      .getMillisDuration("akka.remote.classic.prune-quarantine-marker-after")
      .requiring(_ > Duration.Zero, "prune-quarantine-marker-after must be > 0 ms")
  }

  val CommandAckTimeout: Timeout = {
    Timeout(config.getMillisDuration("akka.remote.classic.command-ack-timeout"))
  }.requiring(_.duration > Duration.Zero, "command-ack-timeout must be > 0")

  val WatchFailureDetectorConfig: Config = getConfig("akka.remote.watch-failure-detector")
  val WatchFailureDetectorImplementationClass: String = WatchFailureDetectorConfig.getString("implementation-class")
  val WatchHeartBeatInterval: FiniteDuration = {
    WatchFailureDetectorConfig.getMillisDuration("heartbeat-interval")
  }.requiring(_ > Duration.Zero, "watch-failure-detector.heartbeat-interval must be > 0")
  val WatchUnreachableReaperInterval: FiniteDuration = {
    WatchFailureDetectorConfig.getMillisDuration("unreachable-nodes-reaper-interval")
  }.requiring(_ > Duration.Zero, "watch-failure-detector.unreachable-nodes-reaper-interval must be > 0")
  val WatchHeartbeatExpectedResponseAfter: FiniteDuration = {
    WatchFailureDetectorConfig.getMillisDuration("expected-response-after")
  }.requiring(_ > Duration.Zero, "watch-failure-detector.expected-response-after > 0")

  val Transports: immutable.Seq[(String, immutable.Seq[String], Config)] = transportNames.map { name =>
    val transportConfig = transportConfigFor(name)
    (
      transportConfig.getString("transport-class"),
      immutableSeq(transportConfig.getStringList("applied-adapters")).reverse,
      transportConfig)
  }

  val Adapters: Map[String, String] = configToMap(getConfig("akka.remote.classic.adapters"))

  private def transportNames: immutable.Seq[String] =
    immutableSeq(getStringList("akka.remote.classic.enabled-transports"))

  private def transportConfigFor(transportName: String): Config = getConfig(transportName)

  private def configToMap(cfg: Config): Map[String, String] =
    cfg.root.unwrapped.asScala.toMap.map { case (k, v) => (k, v.toString) }

}
