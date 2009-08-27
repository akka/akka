/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.amqp

import rabbitmq.client.{AMQP => RabbitMQ, _}
import rabbitmq.client.ConnectionFactory

import se.scalablesolutions.akka.kernel.Kernel
import se.scalablesolutions.akka.kernel.actor.Actor
import se.scalablesolutions.akka.serialization.Serializer

import java.util.{Timer, TimerTask}

object AMQP {
  case class Message(val payload: AnyRef)
  case class AddListener(val listener: Actor)
  case class Reconnect(val delay: Long)

  /**
   * AMQP client actor.
   * Usage:
   * <pre>
   * val params = new ConnectionParameters
   * params.setUsername("bob")
   * params.setPassword("wilber")
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
      case AddListener(listener) => listeners ::= listener
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

class ExampleAMQPEnpointRunner {
  val params = new ConnectionParameters
  params.setUsername("guest")
  params.setPassword("guest")
  params.setVirtualHost("/")
  params.setRequestedHeartbeat(0)
  class ExampleConsumer(channel: Channel, listener: Actor)
  val endpoint = new AMQP.Endpoint(new ConnectionFactory(params), "localhost", 5672) {
    override def init(channel: Channel) = {
      channel.exchangeDeclare("mult", "direct")
      channel.queueDeclare("mult_queue")
      channel.queueBind("mult_queue", "mult", "routeroute")
      channel.basicConsume("mult_queue", false, new DefaultConsumer(channel) {
        private val serializer = Serializer.Java
        override def handleDelivery(tag: String, env: Envelope, props: RabbitMQ.BasicProperties, payload: Array[Byte]) {
          val routingKey = env.getRoutingKey
          val contentType = props.contentType
          val deliveryTag = env.getDeliveryTag
          listener ! AMQP.Message(serializer.in(payload, None))
          channel.basicAck(deliveryTag, false)
        }
      })
    }
  }
  val listener = new Actor() {
    def receive: PartialFunction[Any, Unit] = {
      case AMQP.Message(payload) => println("Received message: " + payload)
    }
  }
  endpoint.start
  listener.start
  endpoint ! AMQP.AddListener(listener)
}
