/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.util.Timeout
import scala.collection.immutable
import akka.util.Helpers.Requiring
import akka.japi.Util._

final class RemoteSettings(val config: Config) {
  import config._
  import scala.collection.JavaConverters._

  val LogReceive: Boolean = getBoolean("akka.remote.log-received-messages")

  val LogSend: Boolean = getBoolean("akka.remote.log-sent-messages")

  val UntrustedMode: Boolean = getBoolean("akka.remote.untrusted-mode")

  val LogRemoteLifecycleEvents: Boolean = getBoolean("akka.remote.log-remote-lifecycle-events")

  val ShutdownTimeout: Timeout = {
    Timeout(Duration(getMilliseconds("akka.remote.shutdown-timeout"), MILLISECONDS))
  } requiring (_.duration > Duration.Zero, "shutdown-timeout must be > 0")

  val FlushWait: FiniteDuration = {
    Duration(getMilliseconds("akka.remote.flush-wait-on-shutdown"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "flush-wait-on-shutdown must be > 0")

  val StartupTimeout: Timeout = {
    Timeout(Duration(getMilliseconds("akka.remote.startup-timeout"), MILLISECONDS))
  } requiring (_.duration > Duration.Zero, "startup-timeout must be > 0")

  val RetryGateClosedFor: FiniteDuration = {
    Duration(getMilliseconds("akka.remote.retry-gate-closed-for"), MILLISECONDS)
  } requiring (_ >= Duration.Zero, "retry-gate-closed-for must be >= 0")

  val UnknownAddressGateClosedFor: FiniteDuration = {
    Duration(getMilliseconds("akka.remote.gate-unknown-addresses-for"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "gate-unknown-addresses-for must be > 0")

  val UsePassiveConnections: Boolean = getBoolean("akka.remote.use-passive-connections")

  val MaximumRetriesInWindow: Int = {
    getInt("akka.remote.maximum-retries-in-window")
  } requiring (_ > 0, "maximum-retries-in-window must be > 0")

  val RetryWindow: FiniteDuration = {
    Duration(getMilliseconds("akka.remote.retry-window"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "retry-window must be > 0")

  val BackoffPeriod: FiniteDuration = {
    Duration(getMilliseconds("akka.remote.backoff-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "backoff-interval must be > 0")

  val SysMsgAckTimeout: FiniteDuration = {
    Duration(getMilliseconds("akka.remote.system-message-ack-piggyback-timeout"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "system-message-ack-piggyback-timeout must be > 0")

  val SysResendTimeout: FiniteDuration = {
    Duration(getMilliseconds("akka.remote.resend-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "resend-interval must be > 0")

  val SysMsgBufferSize: Int = {
    getInt("akka.remote.system-message-buffer-size")
  } requiring (_ > 0, "system-message-buffer-size must be > 0")

  val QuarantineDuration: Duration = {
    if (getString("akka.remote.quarantine-systems-for") == "off") Duration.Undefined
    else Duration(getMilliseconds("akka.remote.quarantine-systems-for"), MILLISECONDS).requiring(_ > Duration.Zero,
      "quarantine-systems-for must be > 0 or off")
  }

  val CommandAckTimeout: Timeout = {
    Timeout(Duration(getMilliseconds("akka.remote.command-ack-timeout"), MILLISECONDS))
  } requiring (_.duration > Duration.Zero, "command-ack-timeout must be > 0")

  val WatchFailureDetectorConfig: Config = getConfig("akka.remote.watch-failure-detector")
  val WatchFailureDetectorImplementationClass: String = WatchFailureDetectorConfig.getString("implementation-class")
  val WatchHeartBeatInterval: FiniteDuration = {
    Duration(WatchFailureDetectorConfig.getMilliseconds("heartbeat-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "watch-failure-detector.heartbeat-interval must be > 0")
  val WatchUnreachableReaperInterval: FiniteDuration = {
    Duration(WatchFailureDetectorConfig.getMilliseconds("unreachable-nodes-reaper-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "watch-failure-detector.unreachable-nodes-reaper-interval must be > 0")
  val WatchHeartbeatExpectedResponseAfter: FiniteDuration = {
    Duration(WatchFailureDetectorConfig.getMilliseconds("expected-response-after"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "watch-failure-detector.expected-response-after > 0")

  val Transports: immutable.Seq[(String, immutable.Seq[String], Config)] = transportNames.map { name ⇒
    val transportConfig = transportConfigFor(name)
    (transportConfig.getString("transport-class"),
      immutableSeq(transportConfig.getStringList("applied-adapters")).reverse,
      transportConfig)
  }

  val Adapters: Map[String, String] = configToMap(getConfig("akka.remote.adapters"))

  private def transportNames: immutable.Seq[String] = immutableSeq(getStringList("akka.remote.enabled-transports"))

  private def transportConfigFor(transportName: String): Config = getConfig(transportName)

  private def configToMap(cfg: Config): Map[String, String] =
    cfg.root.unwrapped.asScala.toMap.map { case (k, v) ⇒ (k, v.toString) }

}
