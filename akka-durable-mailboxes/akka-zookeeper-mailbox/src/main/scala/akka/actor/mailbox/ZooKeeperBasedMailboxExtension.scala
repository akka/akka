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

object ZooKeeperBasedMailboxExtension extends ExtensionId[ZooKeeperBasedMailboxSettings] with ExtensionIdProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new ZooKeeperBasedMailboxSettings(system.applicationConfig)
}
class ZooKeeperBasedMailboxSettings(cfg: Config) extends Extension {
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