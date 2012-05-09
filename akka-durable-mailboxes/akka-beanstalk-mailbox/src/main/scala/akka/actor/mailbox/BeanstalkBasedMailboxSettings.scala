/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorSystem
@deprecated("BeanstalkBasedMailbox will be removed in Akka 2.1", "2.0.2")
class BeanstalkMailboxSettings(val systemSettings: ActorSystem.Settings, val userConfig: Config)
  extends DurableMailboxSettings {

  def name = "beanstalk"

  val config = initialize

  import config._

  val Hostname = getString("hostname")
  val Port = getInt("port")
  val ReconnectWindow = Duration(getMilliseconds("reconnect-window"), MILLISECONDS)
  val MessageSubmitDelay = Duration(getMilliseconds("message-submit-delay"), MILLISECONDS)
  val MessageTimeToLive = Duration(getMilliseconds("message-time-to-live"), MILLISECONDS)

}