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
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

object MongoBasedMailboxExtensionKey extends ExtensionKey[MongoBasedMailboxExtension]

object MongoBasedMailboxExtension {
  def apply(system: ActorSystem): MongoBasedMailboxExtension = {
    if (!system.hasExtension(MongoBasedMailboxExtensionKey)) {
      system.registerExtension(new MongoBasedMailboxExtension)
    }
    system.extension(MongoBasedMailboxExtensionKey)
  }

  class Settings(cfg: Config) {
    private def referenceConfig: Config =
      ConfigFactory.parseResource(classOf[ActorSystem], "/akka-mongo-mailbox-reference.conf",
        ConfigParseOptions.defaults.setAllowMissing(false))
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka-mongo-mailbox").withFallback(cfg).withFallback(referenceConfig).resolve()

    import config._

    val UriConfigKey = "akka.actor.mailbox.mongodb.uri"
    val MongoURI = if (config.hasPath(UriConfigKey)) Some(config.getString(UriConfigKey)) else None
    val WriteTimeout = Duration(config.getMilliseconds("akka.actor.mailbox.mongodb.timeout.write"), MILLISECONDS)
    val ReadTimeout = Duration(config.getMilliseconds("akka.actor.mailbox.mongodb.timeout.read"), MILLISECONDS)

  }
}

class MongoBasedMailboxExtension extends Extension[MongoBasedMailboxExtension] {
  import MongoBasedMailboxExtension._
  @volatile
  private var _settings: Settings = _

  def key = MongoBasedMailboxExtensionKey

  def init(system: ActorSystemImpl) {
    _settings = new Settings(system.applicationConfig)
  }

  def settings: Settings = _settings

}