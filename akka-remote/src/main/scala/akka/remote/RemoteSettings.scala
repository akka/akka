/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import collection.JavaConverters._

class RemoteSettings(val config: Config, val systemName: String) {

  import config._

  val RemoteTransport = getString("akka.remote.transport")
  val LogReceive = getBoolean("akka.remote.log-received-messages")
  val LogSend = getBoolean("akka.remote.log-sent-messages")
  val RemoteSystemDaemonAckTimeout = Duration(getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)
  val UntrustedMode = getBoolean("akka.remote.untrusted-mode")
  val NATFirewall = getString("akka.remote.nat-firewall") match {
    case firewall if firewall == "whitelist" || firewall == "blacklist" ⇒ firewall
    case bad ⇒ throw new IllegalArgumentException("akka.remote.nat-firewall was not set to whitelist or blacklist")
  }
  val NATFirewallAddresses = Set() ++ getStringList("akka.remote.nat-firewall-addresses").asScala
}
