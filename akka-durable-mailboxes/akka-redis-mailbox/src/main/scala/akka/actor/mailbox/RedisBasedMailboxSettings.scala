/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.actor.ActorSystem

class RedisBasedMailboxSettings(val systemSettings: ActorSystem.Settings, val userConfig: Config)
  extends DurableMailboxSettings {

  def name = "redis"

  val config = initialize

  import config._

  val Hostname = getString("hostname")
  val Port = getInt("port")
}