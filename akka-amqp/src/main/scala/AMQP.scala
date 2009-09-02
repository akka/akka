/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import com.rabbitmq.client.{AMQP => RabbitMQ, _}
import com.rabbitmq.client.ConnectionFactory

import kernel.actor.{OneForOneStrategy, Actor}
import kernel.config.ScalaConfig._
import kernel.util.Logging
import serialization.Serializer

import org.scala_tools.javautils.Imports._

import java.util.concurrent.ConcurrentHashMap
import java.util.{Timer, TimerTask}
import java.io.IOException

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
 *   val endpoint = AMQP.newEndpoint(CONFIG, HOSTNAME, PORT, EXCHANGE, QUEUE, ROUTING_KEY, ExchangeType.Direct, Serializer.Java, None, 100)
 *
 *   // register message consumer
 *   endpoint ! MessageConsumer(messageConsumer)
 *
 *   val client = AMQP.newClient(CONFIG, HOSTNAME, PORT, EXCHANGE, Serializer.Java, None, None, 100)
 *   client ! Message("Hi", ROUTING_KEY)
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object AMQP extends Actor {
  private val connections = new ConcurrentHashMap[FaultTolerantConnectionActor, FaultTolerantConnectionActor]
  faultHandler = Some(OneForOneStrategy(5, 5000))
  trapExit = true
  start

  class Message(val payload: AnyRef, val routingKey: String, val mandatory: Boolean, val immediate: Boolean)
  object Message {
    def unapply(message: Message): Option[Tuple4[AnyRef, String, Boolean, Boolean]] =
      Some((message.payload, message.routingKey, message.mandatory, message.immediate))
    def apply(payload: AnyRef, routingKey: String, mandatory: Boolean, immediate: Boolean): Message =
      new Message(payload, routingKey, mandatory, immediate)
    def apply(payload: AnyRef, routingKey: String): Message =
      new Message(payload, routingKey, false, false)
  }
  case class MessageConsumer(listener: Actor)
  case class Reconnect(delay: Long)
  case class Failure(cause: Throwable)
  case object Stop

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

  def newClient(
          config: ConnectionParameters,
          hostname: String,
          port: Int,
          exchangeName: String,
          serializer: Serializer,
          returnListener: Option[ReturnListener],
          shutdownListener: Option[ShutdownListener],
          initReconnectDelay: Long): Client = {
    val client = new Client(
      new ConnectionFactory(config),
      hostname, port,
      exchangeName,
      serializer,
      returnListener,
      shutdownListener,
      initReconnectDelay)
    startLink(client)
    client
  }
  
  def newEndpoint(
          config: ConnectionParameters,
          hostname: String,
          port: Int,
          exchangeName: String,
          queueName: String,
          routingKey: String,
          exchangeType: ExchangeType,
          serializer: Serializer,
          shutdownListener: Option[ShutdownListener],
          initReconnectDelay: Long): Endpoint = {
    val endpoint = new Endpoint(
      new ConnectionFactory(config),
      hostname, port,
      exchangeName, queueName, routingKey,
      exchangeType,
      serializer,
      shutdownListener,
      initReconnectDelay)
    startLink(endpoint)
    endpoint
  }
  
  def stopConnection(connection: FaultTolerantConnectionActor) = {
    connection ! Stop
    unlink(connection)
    connections.remove(connection)
  }

  override def shutdown = {
    connections.values.asScala.foreach(_ ! Stop)
    stop
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
   * val client = AMQP.newClient(params, "localhost", 5672, "exchangeName", Serializer.Java, None, None, 100)
   * client ! Message("hi")
   * </pre>
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class Client private[amqp] (
          val connectionFactory: ConnectionFactory,
          val hostname: String,
          val port: Int,
          exchangeKey: String,
          serializer: Serializer,
          returnListener: Option[ReturnListener],
          shutdownListener: Option[ShutdownListener],
          val initReconnectDelay: Long) 
    extends FaultTolerantConnectionActor {
    var connection = connectionFactory.newConnection(hostname, port)
    var channel = connection.createChannel
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
      case Message(payload, routingKey, mandatory, immediate) =>
        channel.basicPublish(exchangeKey, routingKey, mandatory, immediate, null, serializer.out(payload))
      case Stop =>
        disconnect; stop
    }

    def setupChannel = {}
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class Endpoint private[amqp] (
          val connectionFactory: ConnectionFactory,
          val hostname: String,
          val port: Int,
          exchangeName: String,
          queueName: String,
          routingKey: String,
          exchangeType: ExchangeType,
          serializer: Serializer,
          shutdownListener: Option[ShutdownListener],
          val initReconnectDelay: Long) 
    extends FaultTolerantConnectionActor {
    var connection = connectionFactory.newConnection(hostname, port)
    var channel = connection.createChannel
    var listeners: List[Actor] = Nil
    val endpoint = this
    if (shutdownListener.isDefined) connection.addShutdownListener(shutdownListener.get)
    setupChannel

    def setupChannel = {
      channel.exchangeDeclare(exchangeName, exchangeType.toString)
      channel.queueDeclare(queueName)
      channel.queueBind(queueName, exchangeName, routingKey)
      channel.basicConsume(queueName, false, new DefaultConsumer(channel) with Logging {
        override def handleDelivery(
                tag: String,
                envelope: Envelope,
                properties: RabbitMQ.BasicProperties,
                payload: Array[Byte]) {
          try {
            endpoint ! Message(serializer.in(payload, None), envelope.getRoutingKey)
            channel.basicAck(envelope.getDeliveryTag, false)
          } catch {
            case cause => endpoint ! Failure(cause) // pass on and rethrow exception in endpoint actor to trigger restart and reconnect
          }
        }
      })
    }

    def receive: PartialFunction[Any, Unit] = {
      case MessageConsumer(listener) => listeners ::= listener
      case message: Message =>          listeners.foreach(_ ! message)
      case Reconnect(delay) =>          reconnect(delay)
      case Failure(cause) =>            throw cause
      case Stop =>                      disconnect; stop
      case unknown =>                   throw new IllegalArgumentException("Unknown message to AMQP Endpoint [" + unknown + "]")
    }
  }

  trait FaultTolerantConnectionActor extends Actor { 
    lifeCycleConfig = Some(LifeCycle(Permanent, 100))
    
    val reconnectionTimer = new Timer
   
    var connection: Connection
    var channel: Channel
   
    val connectionFactory: ConnectionFactory
    val hostname: String
    val port: Int
    val initReconnectDelay: Long

    def setupChannel

    protected def disconnect = {
      try {
        channel.close
      } catch { 
        case e: IOException => log.error("Could not close AMQP channel %s:%s", hostname, port)
        case _ => ()
      }
      try {
        connection.close
        log.debug("Disconnected AMQP connection at %s:%s", hostname, port)
      } catch { 
        case e: IOException => log.error("Could not close AMQP connection %s:%s", hostname, port)
        case _ => ()
      }
    }

    protected def reconnect(delay: Long) = {
      disconnect
      try {
        connection = connectionFactory.newConnection(hostname, port)
        channel = connection.createChannel
        setupChannel
        log.debug("Successfully reconnected to AMQP Server %s:%s", hostname, port)
      } catch {
        case e: Exception =>
          val waitInMillis = delay * 2
          val self = this
          log.debug("Trying to reconnect to AMQP server in %n milliseconds", waitInMillis)
          reconnectionTimer.schedule(new TimerTask() { override def run = self ! Reconnect(waitInMillis) }, delay)
      }
    }

    override def preRestart(reason: AnyRef, config: Option[AnyRef]) = disconnect
    override def postRestart(reason: AnyRef, config: Option[AnyRef]) = reconnect(initReconnectDelay)
  }

  def receive: PartialFunction[Any, Unit] = {
    case _ => {} // ignore all messages
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
        case Message(payload, _, _, _) => log.debug("Received message: %s", payload)
      }
    }
    messageConsumer.start

    val endpoint = AMQP.newEndpoint(CONFIG, HOSTNAME, PORT, EXCHANGE, QUEUE, ROUTING_KEY, ExchangeType.Direct, SERIALIZER, None, 100)

    // register message consumer
    endpoint ! MessageConsumer(messageConsumer)

    val client = AMQP.newClient(CONFIG, HOSTNAME, PORT, EXCHANGE, SERIALIZER, None, None, 100)
    client ! Message(ROUTING_KEY + " I'm going surfing", ROUTING_KEY)
    Thread.sleep(1000)
    client ! Message(ROUTING_KEY + " I'm going surfing", ROUTING_KEY)
    Thread.sleep(1000)
    client ! Message(ROUTING_KEY + " I'm going surfing", ROUTING_KEY)
    Thread.sleep(1000)
    client ! Message(ROUTING_KEY + " I'm going surfing", ROUTING_KEY)
  }
}