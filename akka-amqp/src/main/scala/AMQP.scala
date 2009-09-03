/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import com.rabbitmq.client.{AMQP => RabbitMQ, _}
import com.rabbitmq.client.ConnectionFactory

import actor.Actor
import serialization.Serializer

import java.util.{Timer, TimerTask}

object AMQP {
  case class Message(val payload: AnyRef)
  case class MessageConsumer(val listener: Actor)
  case class Reconnect(val delay: Long)

  /**
   * AMQP client actor.
   * Usage:
   * <pre>
   * val params = new ConnectionParameters
   * params.setUsername("barack")
   * params.setPassword("obama")
   * params.setVirtualHost("/")
   * params.setRequestedHeartbeat(0)
   * val client = new AMQP.Client(new ConnectionFactory(params), "localhost", 9889, "exchangeKey", "routingKey", Serializer.Java)
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
          serializer: Serializer) extends Actor {
    private val connection = connectionFactory.newConnection(hostname, port)
    private val channel = connection.createChannel

    def receive: PartialFunction[Any, Unit] = {
      case Message(msg: AnyRef) => send(msg)
    }

    protected def send(message: AnyRef) = channel.basicPublish(exchangeKey, routingKey, null, serializer.out(message))
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  abstract class Endpoint(connectionFactory: ConnectionFactory, hostname: String, port: Int) extends Actor {
    private var connection = connectionFactory.newConnection(hostname, port)
    private var channel = connection.createChannel
    private val reconnectionTimer = new Timer
    private var listeners: List[Actor] = Nil
    private val endpoint = this

    def init(channel: Channel)

    def receive: PartialFunction[Any, Unit] = {
      case message: Message => listeners.foreach(_ ! message)
      case MessageConsumer(listener) => listeners ::= listener
      case Reconnect(delay) => reconnect(delay)
      case unknown => throw new IllegalArgumentException("Unknown message to AMQP dispatcher [" + unknown + "]")
    }

    private def reconnect(delay: Long) = {
      try {
        connection = connectionFactory.newConnection(hostname, port)
        channel = connection.createChannel
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
    CONFIG.setUsername("barack")
    CONFIG.setPassword("obama")
    CONFIG.setVirtualHost("/")
    CONFIG.setRequestedHeartbeat(0)

    val EXCHANGE = "whitehouse.gov"
    val QUEUE = "marketing"
    val ROUTING_KEY = "newsletter"
    val HOSTNAME = "localhost"
    val PORT = 8787
    val SERIALIZER = Serializer.Java

    val messageConsumer = new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload) => println("Received message: " + payload)
      }
    }
    messageConsumer.start

    val endpoint = new Endpoint(new ConnectionFactory(CONFIG), HOSTNAME, PORT) {
      override def init(channel: Channel) = {
        channel.exchangeDeclare(EXCHANGE, "direct")
        channel.queueDeclare(QUEUE)
        channel.queueBind(QUEUE, EXCHANGE, ROUTING_KEY)
        channel.basicConsume(QUEUE, false, new DefaultConsumer(channel) {
          override def handleDelivery(tag: String, envelope: Envelope, properties: RabbitMQ.BasicProperties, payload: Array[Byte]) {
            messageConsumer ! Message(SERIALIZER.in(payload, None))
            channel.basicAck(envelope.getDeliveryTag, false)
          }
        })
      }
    }
    endpoint.start

    endpoint ! MessageConsumer(messageConsumer)

    val client = new Client(new ConnectionFactory(CONFIG), HOSTNAME, PORT, EXCHANGE, ROUTING_KEY, SERIALIZER)
    client.start
    client ! Message("The President: I'm going surfing")
  }
}