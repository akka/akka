/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.rabbitmq.client.ConnectionFactory
import com.typesafe.config.Config
import akka.actor._

object AMQPBasedMailboxExtension extends ExtensionId[AMQPBasedMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): AMQPBasedMailboxSettings = super.get(system)
  def lookup(): ExtensionId[AMQPBasedMailboxSettings] = this
  def createExtension(system: ExtendedActorSystem): AMQPBasedMailboxSettings = {
    new AMQPBasedMailboxSettings(system.settings.config)
  }
}

class AMQPBasedMailboxSettings(val config: Config) extends Extension {

  import config._

  val ConnectionTimeout = getInt("akka.actor.mailbox.amqp.connectionTimeout")
  val Hostname = getString("akka.actor.mailbox.amqp.hostname")
  val Password = getString("akka.actor.mailbox.amqp.password")
  val Port = getInt("akka.actor.mailbox.amqp.port")
  val User = getString("akka.actor.mailbox.amqp.user")
  val VirtualHost = getString("akka.actor.mailbox.amqp.virtualHost")

  val Factory = new ConnectionFactory
  Factory.setUsername(User)
  Factory.setPassword(Password)
  Factory.setVirtualHost(VirtualHost)
  Factory.setHost(Hostname)
  Factory.setPort(Port)
  Factory.setConnectionTimeout(ConnectionTimeout)
}
