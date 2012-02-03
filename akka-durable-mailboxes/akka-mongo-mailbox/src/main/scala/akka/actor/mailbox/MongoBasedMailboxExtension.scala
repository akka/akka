/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import scala.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor._

object MongoBasedMailboxExtension extends ExtensionId[MongoBasedMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): MongoBasedMailboxSettings = super.get(system)
  def lookup() = this
  def createExtension(system: ExtendedActorSystem) = new MongoBasedMailboxSettings(system.settings.config)
}

class MongoBasedMailboxSettings(val config: Config) extends Extension {

  import config._

  val UriConfigKey = "akka.actor.mailbox.mongodb.uri"
  val MongoURI = if (config.hasPath(UriConfigKey)) Some(config.getString(UriConfigKey)) else None
  val WriteTimeout = Duration(config.getMilliseconds("akka.actor.mailbox.mongodb.timeout.write"), MILLISECONDS)
  val ReadTimeout = Duration(config.getMilliseconds("akka.actor.mailbox.mongodb.timeout.read"), MILLISECONDS)

}