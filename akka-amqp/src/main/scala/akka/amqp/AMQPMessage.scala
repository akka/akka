/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{ ReturnListener, Address, ShutdownListener, ShutdownSignalException }
import akka.actor.{ ActorSystem, ActorRef }
import akka.util.Duration
import scala.None

sealed trait AMQPMessage

sealed trait InternalAMQPMessage extends AMQPMessage

case class Message(payload: Seq[Byte], routingKey: String, mandatory: Boolean = false, immediate: Boolean = false,
                   properties: Option[BasicProperties] = None) extends AMQPMessage {

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String) = this(payload, routingKey, false, false, None)

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String, mandatory: Boolean, immediate: Boolean) =
    this(payload, routingKey, mandatory, immediate, None)

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String, properties: BasicProperties) =
    this(payload, routingKey, false, false, Option(properties))

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String, mandatory: Boolean, immediate: Boolean, properties: BasicProperties) =
    this(payload.toSeq, routingKey, mandatory, immediate, Option(properties))
}

case class Delivery(payload: Seq[Byte], routingKey: String, deliveryTag: Long, isRedeliver: Boolean,
                    properties: BasicProperties, sender: Option[ActorRef]) extends AMQPMessage {

  def payloadAsArrayBytes() = payload.toArray
}

// connection messages
case class ConnectionRequest(connectionParameters: ConnectionParameters) extends AMQPMessage {
  def getInstance(): ConnectionRequest = this // Needed for Java API usage
}

case object Connect extends AMQPMessage
case object Disconnect extends AMQPMessage

case object Connected extends AMQPMessage {
  def getInstance(): Connected.type = this // Needed for Java API usage
}
case object Reconnecting extends AMQPMessage {
  def getInstance(): Reconnecting.type = this // Needed for Java API usage
}
case object Disconnected extends AMQPMessage {
  def getInstance(): Disconnected.type = this // Needed for Java API usage
}

case object ChannelRequest extends InternalAMQPMessage

// channel messages
case object Starting extends AMQPMessage {
  def getInstance(): Starting.type = this
}
case object Start extends AMQPMessage {
  def getInstance(): Start.type = this
}
case object Started extends AMQPMessage {
  def getInstance(): Started.type = this // Needed for Java API usage
}
case object Restarting extends AMQPMessage {
  def getInstance(): Restarting.type = this // Needed for Java API usage
}
case object Stopped extends AMQPMessage {
  def getInstance(): Stopped.type = this // Needed for Java API usage
}

// consumer/producer messages

case class ConsumerRequest(consumerParameters: ConsumerParameters) extends AMQPMessage {
  def getInstance(): ConsumerRequest = this // Needed for Java API usage
}

case class ProducerRequest(producerParameters: ProducerParameters) extends AMQPMessage {
  def getInstance(): ProducerRequest = this // Needed for Java API usage
}

// delivery messages
case class Acknowledge(deliveryTag: Long) extends AMQPMessage
case class Acknowledged(deliveryTag: Long) extends AMQPMessage
case class Reject(deliveryTag: Long, requeue: Boolean = false) extends AMQPMessage
case class Rejected(deliveryTag: Long) extends AMQPMessage

// internal messages
private[akka] case class Failure(cause: Throwable) extends InternalAMQPMessage
case class ConnectionShutdown(cause: ShutdownSignalException) extends InternalAMQPMessage
case class ChannelShutdown(cause: ShutdownSignalException) extends InternalAMQPMessage

private[akka] class MessageNotDeliveredException(val message: String) extends RuntimeException(message)

/**
 * Parameters used to make the connection to the amqp broker. Uses the rabbitmq defaults.
 */
case class ConnectionParameters(
  addresses: Option[Seq[Address]] = None,
  username: Option[String] = None,
  password: Option[String] = None,
  virtualHost: Option[String] = None,
  initReconnectDelay: Option[Duration] = None,
  connectionCallback: Option[ActorRef] = None) {

  // Needed for Java API usage
  def this() = this(None, None, None, None, None, None)

  // Needed for Java API usage
  def this(addresses: Seq[Address], username: String, password: String, virtualHost: String) =
    this(Option(addresses), Option(username), Option(password), Option(virtualHost), None, None)

  // Needed for Java API usage
  def this(connectionCallback: ActorRef) =
    this(None, None, None, None, None, Option(connectionCallback))

}

/**
 * Additional parameters for the channel
 */
case class ChannelParameters(
  shutdownListener: Option[ShutdownListener] = None,
  channelCallback: Option[ActorRef] = None,
  prefetchSize: Int = 0) {

  // Needed for Java API usage
  def this() = this(None, None)

  // Needed for Java API usage
  def this(channelCallback: ActorRef) = this(None, Option(channelCallback))

  // Needed for Java API usage
  def this(shutdownListener: ShutdownListener, channelCallback: ActorRef) =
    this(Option(shutdownListener), Option(channelCallback))
}

/**
 * Declaration type used for either exchange or queue declaration
 */
sealed trait Declaration
case object NoActionDeclaration extends Declaration {
  def getInstance(): NoActionDeclaration.type = this // Needed for Java API usage
}
case object PassiveDeclaration extends Declaration {
  def getInstance(): PassiveDeclaration.type = this // Needed for Java API usage
}
case class ActiveDeclaration(durable: Boolean = false, autoDelete: Boolean = true, exclusive: Boolean = false) extends Declaration {

  // Needed for Java API usage
  def this() = this(false, true, false)

  // Needed for Java API usage
  def this(durable: Boolean, autoDelete: Boolean) = this(durable, autoDelete, false)
}

/**
 * Exchange specific parameters
 */
case class ExchangeParameters(
  exchangeName: String,
  exchangeType: ExchangeType = Topic,
  exchangeDeclaration: Declaration = ActiveDeclaration(),
  configurationArguments: Map[String, AnyRef] = Map.empty) {

  // Needed for Java API usage
  def this(exchangeName: String) =
    this(exchangeName, Topic, ActiveDeclaration(), Map.empty)

  // Needed for Java API usage
  def this(exchangeName: String, exchangeType: ExchangeType) =
    this(exchangeName, exchangeType, ActiveDeclaration(), Map.empty)

  // Needed for Java API usage
  def this(exchangeName: String, exchangeType: ExchangeType, exchangeDeclaration: Declaration) =
    this(exchangeName, exchangeType, exchangeDeclaration, Map.empty)
}

/**
 * Producer specific parameters
 */
case class ProducerParameters(
  exchangeParameters: Option[ExchangeParameters] = None,
  returnListener: Option[ReturnListener] = None,
  channelParameters: Option[ChannelParameters] = None,
  errorCallbackActor: Option[ActorRef] = None) {
  def this() = this(None, None, None, None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters) =
    this(Option(exchangeParameters), None, None, None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener) =
    this(Option(exchangeParameters), Option(returnListener), None, None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters) =
    this(Option(exchangeParameters), None, Option(channelParameters), None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener, channelParameters: ChannelParameters) =
    this(Option(exchangeParameters), Option(returnListener), Option(channelParameters), None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener, channelParameters: ChannelParameters, errorCallbackActor: ActorRef) =
    this(Option(exchangeParameters), Option(returnListener), Option(channelParameters), Option(errorCallbackActor))

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters, errorCallbackActor: ActorRef) =
    this(Option(exchangeParameters), None, Option(channelParameters), Option(errorCallbackActor))
}

/**
 * Consumer specific parameters
 */
case class ConsumerParameters(
  routingKey: String,
  deliveryHandler: ActorRef,
  queueName: Option[String] = None,
  exchangeParameters: Option[ExchangeParameters] = None,
  queueDeclaration: Declaration = ActiveDeclaration(),
  selfAcknowledging: Boolean = true,
  channelParameters: Option[ChannelParameters] = None) {
  if (queueName.isEmpty) {
    queueDeclaration match {
      case ActiveDeclaration(true, _, _) ⇒
        throw new IllegalArgumentException("A queue name is required when requesting a durable queue.")
      case PassiveDeclaration ⇒
        throw new IllegalArgumentException("A queue name is required when requesting passive declaration.")
      case _ ⇒ () // ignore
    }
  }
  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef) =
    this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), true, Option(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, selfAcknowledging: Boolean) =
    this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), selfAcknowledging, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), selfAcknowledging, Option(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String) =
    this(routingKey, deliveryHandler, Option(queueName), None, ActiveDeclaration(), true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, queueDeclaration: Declaration, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, Option(queueName), None, queueDeclaration, selfAcknowledging, Option(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters) =
    this(routingKey, deliveryHandler, None, Option(exchangeParameters), ActiveDeclaration(), true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, None, Option(exchangeParameters), ActiveDeclaration(), true, Option(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters, selfAcknowledging: Boolean) =
    this(routingKey, deliveryHandler, None, Option(exchangeParameters), ActiveDeclaration(), selfAcknowledging, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters) =
    this(routingKey, deliveryHandler, Option(queueName), Option(exchangeParameters), ActiveDeclaration(), true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration) =
    this(routingKey, deliveryHandler, Option(queueName), Option(exchangeParameters), queueDeclaration, true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration, selfAcknowledging: Boolean) =
    this(routingKey, deliveryHandler, Option(queueName), Option(exchangeParameters), queueDeclaration, selfAcknowledging, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, Option(queueName), Option(exchangeParameters), queueDeclaration, selfAcknowledging, Option(channelParameters))

  // How about that for some overloading... huh? :P (yes, I know, there are still possibilities left...sue me!)
  // Who said java is easy :(
}