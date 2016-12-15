/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import akka.util.Duration
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
import akka.actor._
import com.rabbitmq.client.Address
import scala.collection.JavaConversions._

class SettingsImpl(config: Config) extends Extension {

  final val Timeout: Duration = Duration(config.getMilliseconds("akka.amqp.timeout"), TimeUnit.MILLISECONDS)
  final val DefaultAddresses: Seq[Address] = config.getStringList("akka.amqp.default.addresses").map { entry ⇒
    entry.split(":") match {
      case Array(host, port) ⇒ new Address(host, port.toInt)
      case Array(host)       ⇒ new Address(host)
      case _                 ⇒ throw new AkkaAMQPException("akka.amqp.default.addresses entry [{" + entry + "}] malformed")
    }
  }
  final val DefaultUser: String = config.getString("akka.amqp.default.user")
  final val DefaultPassword: String = config.getString("akka.amqp.default.password")
  final val DefaultVhost: String = config.getString("akka.amqp.default.virtual-host")
  final val DefaultInitReconnectDelay: Duration = Duration(config.getMilliseconds("akka.amqp.default.init-reconnect-delay"), TimeUnit.MILLISECONDS)

}

object AMQP extends ExtensionId[SettingsImpl] with ExtensionIdProvider {

  override def lookup() = this

  override def createExtension(system: ExtendedActorSystem): SettingsImpl = new SettingsImpl(system.settings.config)

  // for java
  def newConnection(context: ActorContext): ActorRef = newConnection(context, ConnectionParameters(), None)
  def newConnection(context: ActorContext, params: ConnectionParameters): ActorRef = newConnection(context, params, None)
  def newConnection(context: ActorContext, name: String): ActorRef = newConnection(context, ConnectionParameters(), Option(name))
  def newConnection(context: ActorContext, params: ConnectionParameters, name: String): ActorRef = newConnection(context, params, Option(name))

  // for the saner language
  def newConnection(context: ActorContext, params: ConnectionParameters = ConnectionParameters(), name: Option[String] = None): ActorRef = {
    if (name.isDefined)
      context.actorOf(Props(new FaultTolerantConnectionActor(params)), name.get)
    else
      context.actorOf(Props(new FaultTolerantConnectionActor(params)))
  }
}

