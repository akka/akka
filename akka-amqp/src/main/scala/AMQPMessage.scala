/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.ActorRef
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.ShutdownSignalException

sealed trait AMQPMessage
sealed trait InternalAMQPMessage extends AMQPMessage

case class Message(payload: Array[Byte],
                      routingKey: String,
                      mandatory: Boolean = false,
                      immediate: Boolean = false,
                      properties: Option[BasicProperties] = None) extends AMQPMessage

case class Delivery(payload: Array[Byte],
                        routingKey: String,
                        deliveryTag: Long,
                        properties: BasicProperties,
                        sender: Option[ActorRef]) extends AMQPMessage



// connection messages
case object Connect extends AMQPMessage

case object Connected extends AMQPMessage
case object Reconnecting extends AMQPMessage
case object Disconnected extends AMQPMessage

case object ChannelRequest extends InternalAMQPMessage

// channel messages
case object Start extends AMQPMessage

case object Started extends AMQPMessage
case object Restarting extends AMQPMessage
case object Stopped extends AMQPMessage

// delivery messages
case class Acknowledge(deliveryTag: Long) extends AMQPMessage
case class Acknowledged(deliveryTag: Long) extends AMQPMessage

// internal messages
private[akka] case class Failure(cause: Throwable) extends InternalAMQPMessage
private[akka] case class ConnectionShutdown(cause: ShutdownSignalException) extends InternalAMQPMessage
private[akka] case class ChannelShutdown(cause: ShutdownSignalException) extends InternalAMQPMessage

private[akka] class MessageNotDeliveredException(
        val message: String,
        val replyCode: Int,
        val replyText: String,
        val exchange: String,
        val routingKey: String,
        val properties: BasicProperties,
        val body: Array[Byte]) extends RuntimeException(message)
