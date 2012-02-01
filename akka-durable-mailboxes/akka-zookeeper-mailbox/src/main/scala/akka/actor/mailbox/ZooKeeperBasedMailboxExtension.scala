/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import scala.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor._

object ZooKeeperBasedMailboxExtension extends ExtensionId[ZooKeeperBasedMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): ZooKeeperBasedMailboxSettings = super.get(system)
  def lookup() = this
  def createExtension(system: ExtendedActorSystem) = new ZooKeeperBasedMailboxSettings(system.settings.config)
}
class ZooKeeperBasedMailboxSettings(val config: Config) extends Extension {

  import config._

  val ZkServerAddresses = getString("akka.actor.mailbox.zookeeper.server-addresses")
  val SessionTimeout = Duration(getMilliseconds("akka.actor.mailbox.zookeeper.session-timeout"), MILLISECONDS)
  val ConnectionTimeout = Duration(getMilliseconds("akka.actor.mailbox.zookeeper.connection-timeout"), MILLISECONDS)
  val BlockingQueue = getBoolean("akka.actor.mailbox.zookeeper.blocking-queue")

}