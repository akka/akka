/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.actor._

object AMQPBasedMailboxExtension extends ExtensionId[AMQPBasedMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): AMQPBasedMailboxSettings = super.get(system)
  def lookup() = this
  def createExtension(system: ExtendedActorSystem) = new AMQPBasedMailboxSettings(system.settings.config)
}

class AMQPBasedMailboxSettings(val config: Config) extends Extension {

  import config._

  val Hostname = getString("akka.actor.mailbox.amqp.hostname")
  val Port = getInt("akka.actor.mailbox.amqp.port")
  val User = getString("akka.actor.mailbox.amqp.user")
  val Password = getString("akka.actor.mailbox.amqp.password")
  val VirtualHost = getString("akka.actor.mailbox.amqp.virtualHost")
}
