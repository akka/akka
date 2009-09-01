/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import java.lang.String
import com.rabbitmq.client.{AMQP => RabbitMQ, _}
import com.rabbitmq.client.ConnectionFactory

import se.scalablesolutions.akka.kernel.actor.Actor
import se.scalablesolutions.akka.kernel.util.Logging
import se.scalablesolutions.akka.serialization.Serializer

import java.util.{Timer, TimerTask}

/**
 * AMQP Actor API. Implements Client and Endpoint materialized as Actors.
 * 
 * <pre>
 *   val messageConsumer = new Actor() {
 *     def receive: PartialFunction[Any, Unit] = {
 *       case Message(payload) => log.debug("Received message: %s", payload)
 *     }
 *   }
 *   messageConsumer.start
 *
 *   val endpoint = new Endpoint(
 *     new ConnectionFactory(CONFIG), HOSTNAME, PORT, EXCHANGE, QUEUE, ROUTING_KEY, ExchangeType.Direct, Serializer.Java)
 *   endpoint.start
 *
 *   // register message consumer
 *   endpoint ! MessageConsumer(messageConsumer)
 *
 *   val client = new Client(new ConnectionFactory(CONFIG), HOSTNAME, PORT, EXCHANGE, ROUTING_KEY, Serializer.Java, None)
 *   client.start
 *   client ! Message("Hi")
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object AMQP {
  case class Message(val payload: AnyRef)
  case class MessageConsumer(val listener: Actor)
  case class Reconnect(val delay: Long)

  class MessageNotDeliveredException(message: String) extends RuntimeException(message)

  sealed trait ExchangeType  
  object ExchangeType {
    case object Direct extends ExchangeType {
      override def toString = "direct"
    }
    case object Topic extends ExchangeType {
      override def toString = "topic"      
    }
    case object Fanout extends ExchangeType {
      override def toString = "fanout"
    }
    case object Match extends ExchangeType {
      override def toString = "match"
    }
  }

  /**
   * AMQP client actor.
   * Usage:
   * <pre>
   * val params = new ConnectionParameters
   * params.setUsername("barack")
   * params.setPassword("obama")
   * params.setVirtualHost("/")
   * params.setRequestedHeartbeat(0)
   * val client = new AMQP.Client(new ConnectionFactory(params), "localhost", 5672, "exchangeName", "routingKey", Serializer.Java, None, None)
   * client.start
   * client ! Message("hi")
   * </pre>
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class Client(
          connectionFactory: ConnectionFactory,
          hostname: String,
          port: Int,
          exchangeKey: String,
          routingKey: String,
          serializer: Serializer,
          returnListener: Option[ReturnListener],
          shutdownListener: Option[ShutdownListener]) extends Actor {
    private val connection = connectionFactory.newConnection(hostname, port)
    private val channel = connection.createChannel
    returnListener match {
      case Some(listener) => channel.setReturnListener(listener)
      case None => channel.setReturnListener(new ReturnListener() {
        def handleBasicReturn(
                replyCode: Int,
                replyText: String,
                exchange: String,
                routingKey: String,
                properties: RabbitMQ.BasicProperties,
                body: Array[Byte]) = {
          throw new MessageNotDeliveredException(
            "Could not deliver message [" + replyText +
                    "] with reply code [" + replyCode +
                    "] and routing key [" + routingKey +
                    "] to exchange [" + exchange + "]")
        }
      })
    }
    if (shutdownListener.isDefined) connection.addShutdownListener(shutdownListener.get)

    def receive: PartialFunction[Any, Unit] = {
      case Message(msg: AnyRef) => send(msg)
    }

    protected def send(message: AnyRef) = channel.basicPublish(exchangeKey, routingKey, null, serializer.out(message))
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class Endpoint(
          connectionFactory: ConnectionFactory,
          hostname: String,
          port: Int,
          exchangeName: String,
          queueName: String,
          routingKey: String,
          exchangeType: ExchangeType,
          serializer: Serializer,
          shutdownListener: Option[ShutdownListener]) extends Actor {
    private var connection = connectionFactory.newConnection(hostname, port)
    private var channel = connection.createChannel
    private val reconnectionTimer = new Timer
    private var listeners: List[Actor] = Nil
    private val endpoint = this
    if (shutdownListener.isDefined) connection.addShutdownListener(shutdownListener.get)
    setupChannel

    private def setupChannel = {
      channel.exchangeDeclare(exchangeName, exchangeType.toString)
      channel.queueDeclare(queueName)
      channel.queueBind(queueName, exchangeName, routingKey)
      channel.basicConsume(queueName, false, new DefaultConsumer(channel) with Logging {
        override def handleDelivery(
                tag: String,
                envelope: Envelope,
                properties: RabbitMQ.BasicProperties,
                payload: Array[Byte]) {
          endpoint ! Message(serializer.in(payload, None))
          channel.basicAck(envelope.getDeliveryTag, false)
        }
      })
    }

    def receive: PartialFunction[Any, Unit] = {
      case message: Message => listeners.foreach(_ ! message)
      case MessageConsumer(listener) => listeners ::= listener
      case Reconnect(delay) => reconnect(delay)
      case unknown => throw new IllegalArgumentException("Unknown message to AMQP Endpoint [" + unknown + "]")
    }

    private def reconnect(delay: Long) = {
      try {
        connection = connectionFactory.newConnection(hostname, port)
        channel = connection.createChannel
        setupChannel
        log.debug("Successfully reconnected to AMQP Server")
      } catch {
        case e: Exception =>
          val waitInMillis = delay * 2
          log.debug("Trying to reconnect to AMQP server in %n milliseconds" + waitInMillis)
          reconnectionTimer.schedule(new TimerTask() {override def run = endpoint ! Reconnect(waitInMillis)}, delay)
      }
    }
  }
}

object ExampleAMQPSession {
  def main(args: Array[String]) = {
    import AMQP._
    val CONFIG = new ConnectionParameters
    val EXCHANGE = "whitehouse.gov"
    val QUEUE = "twitter"
    val ROUTING_KEY = "@barack_obama"
    val HOSTNAME = "localhost"
    val PORT = 5672
    val SERIALIZER = Serializer.Java

    val messageConsumer = new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload) => log.debug("Received message: %s", payload)
      }
    }
    messageConsumer.start

    val endpoint = new Endpoint(
      new ConnectionFactory(CONFIG), HOSTNAME, PORT, EXCHANGE, QUEUE, ROUTING_KEY, ExchangeType.Direct, SERIALIZER, None)
    endpoint.start

    // register message consumer
    endpoint ! MessageConsumer(messageConsumer)

    val client = new Client(new ConnectionFactory(CONFIG), HOSTNAME, PORT, EXCHANGE, ROUTING_KEY, SERIALIZER, None, None)
    client.start
    client ! Message(ROUTING_KEY + " I'm going surfing")
  }
}