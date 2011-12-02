/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.actor._

object RedisBasedMailboxExtension extends ExtensionId[RedisBasedMailboxSettings] with ExtensionIdProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new RedisBasedMailboxSettings(system.settings.config)
}

class RedisBasedMailboxSettings(val config: Config) extends Extension {

  import config._

  val Hostname = getString("akka.actor.mailbox.redis.hostname")
  val Port = getInt("akka.actor.mailbox.redis.port")
}