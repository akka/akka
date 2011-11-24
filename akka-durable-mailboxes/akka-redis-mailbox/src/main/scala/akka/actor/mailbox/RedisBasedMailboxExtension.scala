/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.ActorSystem
import akka.actor.ExtensionKey
import akka.actor.Extension
import akka.actor.ActorSystemImpl
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot

object RedisBasedMailboxExtensionKey extends ExtensionKey[RedisBasedMailboxExtension]

object RedisBasedMailboxExtension {
  def apply(system: ActorSystem): RedisBasedMailboxExtension = {
    if (!system.hasExtension(RedisBasedMailboxExtensionKey)) {
      system.registerExtension(new RedisBasedMailboxExtension)
    }
    system.extension(RedisBasedMailboxExtensionKey)
  }

  class Settings(cfg: Config) {
    private def referenceConfig: Config =
      ConfigFactory.parseResource(classOf[ActorSystem], "/akka-redis-mailbox-reference.conf",
        ConfigParseOptions.defaults.setAllowMissing(false))
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka-redis-mailbox").withFallback(cfg).withFallback(referenceConfig).resolve()

    import config._

    val Hostname = getString("akka.actor.mailbox.redis.hostname")
    val Port = getInt("akka.actor.mailbox.redis.port")

  }
}

class RedisBasedMailboxExtension extends Extension[RedisBasedMailboxExtension] {
  import RedisBasedMailboxExtension._
  @volatile
  private var _settings: Settings = _

  def key = RedisBasedMailboxExtensionKey

  def init(system: ActorSystemImpl) {
    _settings = new Settings(system.applicationConfig)
  }

  def settings: Settings = _settings

}