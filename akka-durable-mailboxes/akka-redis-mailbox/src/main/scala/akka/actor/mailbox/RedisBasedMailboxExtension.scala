/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.actor._

object RedisBasedMailboxExtension extends ExtensionId[RedisBasedMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): RedisBasedMailboxSettings = super.get(system)
  def lookup() = this
  def createExtension(system: ExtendedActorSystem) = new RedisBasedMailboxSettings(system.settings.config)
}

class RedisBasedMailboxSettings(val config: Config) extends Extension {

  import config._

  val Hostname = getString("akka.actor.mailbox.redis.hostname")
  val Port = getInt("akka.actor.mailbox.redis.port")
}