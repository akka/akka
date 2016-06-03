/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import com.typesafe.config.Config
import scala.concurrent.duration._
import akka.util.Timeout
import scala.collection.immutable
import akka.util.Helpers.{ ConfigOps, Requiring, toRootLowerCase }
import akka.japi.Util._
import akka.actor.Props
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.ConfigurationException

final class RemoteSettings(val config: Config) {
  import config._
  import scala.collection.JavaConverters._

  val LogReceive: Boolean = getBoolean("akka.remote.log-received-messages")

  val LogSend: Boolean = getBoolean("akka.remote.log-sent-messages")

  val UntrustedMode: Boolean = getBoolean("akka.remote.untrusted-mode")

  val TrustedSelectionPaths: Set[String] =
    immutableSeq(getStringList("akka.remote.trusted-selection-paths")).toSet

  val RemoteLifecycleEventsLogLevel: LogLevel = toRootLowerCase(getString("akka.remote.log-remote-lifecycle-events")) match {
    case "on" ⇒ Logging.DebugLevel
    case other ⇒ Logging.levelFor(other) match {
      case Some(level) ⇒ level
      case None        ⇒ throw new ConfigurationException("Logging level must be one of (on, off, debug, info, warning, error)")
    }
  }

  val Dispatcher: String = getString("akka.remote.use-dispatcher")

  def configureDispatcher(props: Props): Props = if (Dispatcher.isEmpty) props else props.withDispatcher(Dispatcher)

  val ShutdownTimeout: Timeout = {
    Timeout(config.getMillisDuration("akka.remote.shutdown-timeout"))
  } requiring (_.duration > Duration.Zero, "shutdown-timeout must be > 0")

  val FlushWait: FiniteDuration = {
    config.getMillisDuration("akka.remote.flush-wait-on-shutdown")
  } requiring (_ > Duration.Zero, "flush-wait-on-shutdown must be > 0")

  val StartupTimeout: Timeout = {
    Timeout(config.getMillisDuration("akka.remote.startup-timeout"))
  } requiring (_.duration > Duration.Zero, "startup-timeout must be > 0")

  val RetryGateClosedFor: FiniteDuration = {
    config.getMillisDuration("akka.remote.retry-gate-closed-for")
  } requiring (_ >= Duration.Zero, "retry-gate-closed-for must be >= 0")

  val UsePassiveConnections: Boolean = getBoolean("akka.remote.use-passive-connections")

  val BackoffPeriod: FiniteDuration = {
    config.getMillisDuration("akka.remote.backoff-interval")
  } requiring (_ > Duration.Zero, "backoff-interval must be > 0")

  val LogBufferSizeExceeding: Int = {
    val key = "akka.remote.log-buffer-size-exceeding"
    config.getString(key).toLowerCase match {
      case "off" | "false" ⇒ Int.MaxValue
      case _               ⇒ config.getInt(key)
    }
  }

  val SysMsgAckTimeout: FiniteDuration = {
    config.getMillisDuration("akka.remote.system-message-ack-piggyback-timeout")
  } requiring (_ > Duration.Zero, "system-message-ack-piggyback-timeout must be > 0")

  val SysResendTimeout: FiniteDuration = {
    config.getMillisDuration("akka.remote.resend-interval")
  } requiring (_ > Duration.Zero, "resend-interval must be > 0")

  val SysResendLimit: Int = {
    config.getInt("akka.remote.resend-limit")
  } requiring (_ > 0, "resend-limit must be > 0")

  val SysMsgBufferSize: Int = {
    getInt("akka.remote.system-message-buffer-size")
  } requiring (_ > 0, "system-message-buffer-size must be > 0")

  val InitialSysMsgDeliveryTimeout: FiniteDuration = {
    config.getMillisDuration("akka.remote.initial-system-message-delivery-timeout")
  } requiring (_ > Duration.Zero, "initial-system-message-delivery-timeout must be > 0")

  val QuarantineSilentSystemTimeout: FiniteDuration = {
    config.getMillisDuration("akka.remote.quarantine-after-silence")
  } requiring (_ > Duration.Zero, "quarantine-after-silence must be > 0")

  val QuarantineDuration: FiniteDuration = {
    config.getMillisDuration("akka.remote.prune-quarantine-marker-after").requiring(
      _ > Duration.Zero,
      "prune-quarantine-marker-after must be > 0 ms")
  }

  val CommandAckTimeout: Timeout = {
    Timeout(config.getMillisDuration("akka.remote.command-ack-timeout"))
  } requiring (_.duration > Duration.Zero, "command-ack-timeout must be > 0")

  val WatchFailureDetectorConfig: Config = getConfig("akka.remote.watch-failure-detector")
  val WatchFailureDetectorImplementationClass: String = WatchFailureDetectorConfig.getString("implementation-class")
  val WatchHeartBeatInterval: FiniteDuration = {
    WatchFailureDetectorConfig.getMillisDuration("heartbeat-interval")
  } requiring (_ > Duration.Zero, "watch-failure-detector.heartbeat-interval must be > 0")
  val WatchUnreachableReaperInterval: FiniteDuration = {
    WatchFailureDetectorConfig.getMillisDuration("unreachable-nodes-reaper-interval")
  } requiring (_ > Duration.Zero, "watch-failure-detector.unreachable-nodes-reaper-interval must be > 0")
  val WatchHeartbeatExpectedResponseAfter: FiniteDuration = {
    WatchFailureDetectorConfig.getMillisDuration("expected-response-after")
  } requiring (_ > Duration.Zero, "watch-failure-detector.expected-response-after > 0")

  val Transports: immutable.Seq[(String, immutable.Seq[String], Config)] = transportNames.map { name ⇒
    val transportConfig = transportConfigFor(name)
    (
      transportConfig.getString("transport-class"),
      immutableSeq(transportConfig.getStringList("applied-adapters")).reverse,
      transportConfig)
  }

  val Adapters: Map[String, String] = configToMap(getConfig("akka.remote.adapters"))

  private def transportNames: immutable.Seq[String] = immutableSeq(getStringList("akka.remote.enabled-transports"))

  private def transportConfigFor(transportName: String): Config = getConfig(transportName)

  private def configToMap(cfg: Config): Map[String, String] =
    cfg.root.unwrapped.asScala.toMap.map { case (k, v) ⇒ (k, v.toString) }

}
