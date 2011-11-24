/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import akka.actor.{ ExtensionProvider, ActorSystem, Extension, ActorSystemImpl }

object RedisBasedMailboxExtension extends Extension[RedisBasedMailboxSettings] with ExtensionProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new RedisBasedMailboxSettings(system.applicationConfig)
}

class RedisBasedMailboxSettings(cfg: Config) {
  private def referenceConfig: Config =
    ConfigFactory.parseResource(classOf[ActorSystem], "/akka-redis-mailbox-reference.conf",
      ConfigParseOptions.defaults.setAllowMissing(false))
  val config: ConfigRoot = ConfigFactory.emptyRoot("akka-redis-mailbox").withFallback(cfg).withFallback(referenceConfig).resolve()

  import config._

  val Hostname = getString("akka.actor.mailbox.redis.hostname")
  val Port = getInt("akka.actor.mailbox.redis.port")
}