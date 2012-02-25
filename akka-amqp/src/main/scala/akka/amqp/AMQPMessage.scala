/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import akka.actor.ActorRef
import com.rabbitmq.client.ConnectionFactory._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{ ReturnListener, Address, ShutdownListener, ShutdownSignalException }

sealed trait AMQPMessage
sealed trait InternalAMQPMessage extends AMQPMessage

case class Message(
  payload: Array[Byte],
  routingKey: String,
  mandatory: Boolean = false,
  immediate: Boolean = false,
  properties: Option[BasicProperties] = None) extends AMQPMessage {

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String) = this(payload, routingKey, false, false, None)

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String, mandatory: Boolean, immediate: Boolean) =
    this(payload, routingKey, mandatory, immediate, None)

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String, properties: BasicProperties) =
    this(payload, routingKey, false, false, Some(properties))

  // Needed for Java API usage
  def this(payload: Array[Byte], routingKey: String, mandatory: Boolean, immediate: Boolean, properties: BasicProperties) =
    this(payload, routingKey, mandatory, immediate, Some(properties))
}

case class Delivery(
  payload: Array[Byte],
  routingKey: String,
  deliveryTag: Long,
  isRedeliver: Boolean,
  properties: BasicProperties,
  sender: Option[ActorRef]) extends AMQPMessage

// connection messages
case class ConnectionRequest(connectionParameters: ConnectionParameters) extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}

case object Connect extends AMQPMessage
case object Disconnect extends AMQPMessage

case object Connected extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}
case object Reconnecting extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}
case object Disconnected extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}

case object ChannelRequest extends InternalAMQPMessage

// channel messages
case object Start extends AMQPMessage

case object Started extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}
case object Restarting extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}
case object Stopped extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}

// consumer/producer messages

case class ConsumerRequest(consumerParameters: ConsumerParameters) extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
}

case class ProducerRequest(producerParameters: ProducerParameters) extends AMQPMessage {
  def getInstance() = this // Needed for Java API usage
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
  addresses: Seq[Address] = Seq(new Address(DEFAULT_HOST, DEFAULT_AMQP_PORT)),
  username: String = DEFAULT_USER,
  password: String = DEFAULT_PASS,
  virtualHost: String = DEFAULT_VHOST,
  initReconnectDelay: Long = 5000,
  connectionCallback: Option[ActorRef] = None) {

  // Needed for Java API usage
  def this() = this(Seq(new Address(DEFAULT_HOST, DEFAULT_AMQP_PORT)), DEFAULT_USER, DEFAULT_PASS, DEFAULT_VHOST, 5000, None)

  // Needed for Java API usage
  def this(addresses: Seq[Address], username: String, password: String, virtualHost: String) =
    this(addresses, username, password, virtualHost, 5000, None)

  // Needed for Java API usage
  def this(addresses: Seq[Address], username: String, password: String, virtualHost: String, initReconnectDelay: Long, connectionCallback: ActorRef) =
    this(addresses, username, password, virtualHost, initReconnectDelay, Some(connectionCallback))

  // Needed for Java API usage
  def this(connectionCallback: ActorRef) =
    this(Seq(new Address(DEFAULT_HOST, DEFAULT_AMQP_PORT)), DEFAULT_USER, DEFAULT_PASS, DEFAULT_VHOST, 5000, Some(connectionCallback))

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
  def this(channelCallback: ActorRef) = this(None, Some(channelCallback))

  // Needed for Java API usage
  def this(shutdownListener: ShutdownListener, channelCallback: ActorRef) =
    this(Some(shutdownListener), Some(channelCallback))
}

/**
 * Declaration type used for either exchange or queue declaration
 */
sealed trait Declaration
case object NoActionDeclaration extends Declaration {
  def getInstance() = this // Needed for Java API usage
}
case object PassiveDeclaration extends Declaration {
  def getInstance() = this // Needed for Java API usage
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
    this(Some(exchangeParameters), None, None, None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener) =
    this(Some(exchangeParameters), Some(returnListener), None, None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters) =
    this(Some(exchangeParameters), None, Some(channelParameters), None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener, channelParameters: ChannelParameters) =
    this(Some(exchangeParameters), Some(returnListener), Some(channelParameters), None)

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener, channelParameters: ChannelParameters, errorCallbackActor: ActorRef) =
    this(Some(exchangeParameters), Some(returnListener), Some(channelParameters), Some(errorCallbackActor))

  // Needed for Java API usage
  def this(exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters, errorCallbackActor: ActorRef) =
    this(Some(exchangeParameters), None, Some(channelParameters), Some(errorCallbackActor))
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
    this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), true, Some(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, selfAcknowledging: Boolean) =
    this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), selfAcknowledging, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), selfAcknowledging, Some(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String) =
    this(routingKey, deliveryHandler, Some(queueName), None, ActiveDeclaration(), true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, queueDeclaration: Declaration, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, Some(queueName), None, queueDeclaration, selfAcknowledging, Some(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters) =
    this(routingKey, deliveryHandler, None, Some(exchangeParameters), ActiveDeclaration(), true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, None, Some(exchangeParameters), ActiveDeclaration(), true, Some(channelParameters))

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters, selfAcknowledging: Boolean) =
    this(routingKey, deliveryHandler, None, Some(exchangeParameters), ActiveDeclaration(), selfAcknowledging, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters) =
    this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), ActiveDeclaration(), true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration) =
    this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), queueDeclaration, true, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration, selfAcknowledging: Boolean) =
    this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), queueDeclaration, selfAcknowledging, None)

  // Needed for Java API usage
  def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
    this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), queueDeclaration, selfAcknowledging, Some(channelParameters))

  // How about that for some overloading... huh? :P (yes, I know, there are still possibilities left...sue me!)
  // Who said java is easy :(
}