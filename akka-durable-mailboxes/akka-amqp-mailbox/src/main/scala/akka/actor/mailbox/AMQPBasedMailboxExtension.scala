/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.rabbitmq.client.ConnectionFactory
import com.typesafe.config.Config
import akka.actor._
import akka.event.Logging

object AMQPBasedMailboxExtension extends ExtensionId[AMQPBasedMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): AMQPBasedMailboxSettings = super.get(system)
  def lookup(): ExtensionId[AMQPBasedMailboxSettings] = this
  def createExtension(system: ExtendedActorSystem): AMQPBasedMailboxSettings = {
    new AMQPBasedMailboxSettings(system)
  }
}

class AMQPBasedMailboxSettings(val system: ExtendedActorSystem) extends Extension {

  private val config = system.settings.config
  private val log = Logging(system, "AMQPBasedMailbox")

  import config._

  val ConnectionTimeout = config.getMilliseconds("akka.actor.mailbox.amqp.connectionTimeout")
  val Hostname = getString("akka.actor.mailbox.amqp.hostname")
  val Password = getString("akka.actor.mailbox.amqp.password")
  val Port = getInt("akka.actor.mailbox.amqp.port")
  val User = getString("akka.actor.mailbox.amqp.user")
  val VirtualHost = getString("akka.actor.mailbox.amqp.virtualHost")

  private val factory = new ConnectionFactory
  factory.setUsername(User)
  factory.setPassword(Password)
  factory.setVirtualHost(VirtualHost)
  factory.setHost(Hostname)
  factory.setPort(Port)
  factory.setConnectionTimeout(ConnectionTimeout.toInt)

  val ChannelPool = new AMQPChannelPool(factory, log)
}
