/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

import akka.actor.ActorRef
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.ShutdownSignalException
import akka.amqp.AMQP.{ ProducerParameters, ConsumerParameters }

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

private[akka] class MessageNotDeliveredException(
  val message: String,
  val replyCode: Int,
  val replyText: String,
  val exchange: String,
  val routingKey: String,
  val properties: BasicProperties,
  val body: Array[Byte]) extends RuntimeException(message)
