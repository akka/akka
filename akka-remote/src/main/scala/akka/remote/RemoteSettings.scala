/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import scala.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.net.InetAddress
import akka.config.ConfigurationException
import scala.collection.JavaConverters._
import akka.actor.Address
import akka.actor.AddressExtractor

class RemoteSettings(val config: Config, val systemName: String) {
  import config._
  val RemoteTransport = getString("akka.remote.transport")
  val LogReceive = getBoolean("akka.remote.log-received-messages")
  val LogSend = getBoolean("akka.remote.log-sent-messages")
  val RemoteSystemDaemonAckTimeout = Duration(getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)
  val UntrustedMode = getBoolean("akka.remote.untrusted-mode")
}
