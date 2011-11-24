/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor._

object MongoBasedMailboxExtension extends Extension[MongoBasedMailboxSettings] with ExtensionProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new MongoBasedMailboxSettings(system.applicationConfig)
}

class MongoBasedMailboxSettings(cfg: Config) {
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