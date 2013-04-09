/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.util.Timeout
import scala.collection.immutable
import akka.japi.Util._

class RemoteSettings(val config: Config) {
  import config._
  import scala.collection.JavaConverters._

  val LogReceive: Boolean = getBoolean("akka.remote.log-received-messages")

  val LogSend: Boolean = getBoolean("akka.remote.log-sent-messages")

  val UntrustedMode: Boolean = getBoolean("akka.remote.untrusted-mode")

  val LogRemoteLifecycleEvents: Boolean = getBoolean("akka.remote.log-remote-lifecycle-events")

  val ShutdownTimeout: Timeout =
    Duration(getMilliseconds("akka.remote.shutdown-timeout"), MILLISECONDS)

  val FlushWait: FiniteDuration = Duration(getMilliseconds("akka.remote.flush-wait-on-shutdown"), MILLISECONDS)

  val StartupTimeout: Timeout = Timeout(Duration(getMilliseconds("akka.remote.startup-timeout"), MILLISECONDS))

  val RetryGateClosedFor: FiniteDuration = Duration(getMilliseconds("akka.remote.retry-gate-closed-for"), MILLISECONDS)

  val UnknownAddressGateClosedFor: FiniteDuration = Duration(getMilliseconds("akka.remote.gate-unknown-addresses-for"), MILLISECONDS)

  val UsePassiveConnections: Boolean = getBoolean("akka.remote.use-passive-connections")

  val MaximumRetriesInWindow: Int = getInt("akka.remote.maximum-retries-in-window")

  val RetryWindow: FiniteDuration = Duration(getMilliseconds("akka.remote.retry-window"), MILLISECONDS)

  val BackoffPeriod: FiniteDuration = Duration(getMilliseconds("akka.remote.backoff-interval"), MILLISECONDS)

  val CommandAckTimeout: Timeout =
    Timeout(Duration(getMilliseconds("akka.remote.command-ack-timeout"), MILLISECONDS))

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
