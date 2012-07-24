/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

class RemoteSettings(val config: Config, val systemName: String) {
  import config._
  val RemoteTransport: String = getString("akka.remote.transport")
  val LogReceive: Boolean = getBoolean("akka.remote.log-received-messages")
  val LogSend: Boolean = getBoolean("akka.remote.log-sent-messages")
  val RemoteSystemDaemonAckTimeout: Duration = Duration(getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)
  val UntrustedMode: Boolean = getBoolean("akka.remote.untrusted-mode")
  val LogRemoteLifeCycleEvents: Boolean = getBoolean("akka.remote.log-remote-lifecycle-events")
}
