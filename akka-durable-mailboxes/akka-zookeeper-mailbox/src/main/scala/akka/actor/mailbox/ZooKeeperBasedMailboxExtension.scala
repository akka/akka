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

object ZooKeeperBasedMailboxExtensionKey extends ExtensionKey[ZooKeeperBasedMailboxExtension]

object ZooKeeperBasedMailboxExtension {
  def apply(system: ActorSystem): ZooKeeperBasedMailboxExtension = {
    if (!system.hasExtension(ZooKeeperBasedMailboxExtensionKey)) {
      system.registerExtension(new ZooKeeperBasedMailboxExtension)
    }
    system.extension(ZooKeeperBasedMailboxExtensionKey)
  }

  class Settings(cfg: Config) {
    private def referenceConfig: Config =
      ConfigFactory.parseResource(classOf[ActorSystem], "/akka-zookeeper-mailbox-reference.conf",
        ConfigParseOptions.defaults.setAllowMissing(false))
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka-zookeeper-mailbox").withFallback(cfg).withFallback(referenceConfig).resolve()

    import config._

    val ZkServerAddresses = getString("akka.actor.mailbox.zookeeper.server-addresses")
    val SessionTimeout = Duration(getMilliseconds("akka.actor.mailbox.zookeeper.session-timeout"), MILLISECONDS)
    val ConnectionTimeout = Duration(getMilliseconds("akka.actor.mailbox.zookeeper.connection-timeout"), MILLISECONDS)
    val BlockingQueue = getBoolean("akka.actor.mailbox.zookeeper.blocking-queue")

  }
}

class ZooKeeperBasedMailboxExtension extends Extension[ZooKeeperBasedMailboxExtension] {
  import ZooKeeperBasedMailboxExtension._
  @volatile
  private var _settings: Settings = _

  def key = ZooKeeperBasedMailboxExtensionKey

  def init(system: ActorSystemImpl) {
    _settings = new Settings(system.applicationConfig)
  }

  def settings: Settings = _settings

}