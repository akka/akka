/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import com.rabbitmq.client.{AMQP => RabbitMQ, _}
import com.rabbitmq.client.ConnectionFactory

import actor.{OneForOneStrategy, Actor}
import config.ScalaConfig._
import util.{HashCode, Logging}
import serialization.Serializer

import scala.collection.mutable.HashMap

import org.scala_tools.javautils.Imports._

import java.util.concurrent.ConcurrentHashMap
import java.util.{Timer, TimerTask}
import java.io.IOException

/**
 * AMQP Actor API. Implements Client and Endpoint materialized as Actors.
 *
 * <pre>
 *   val endpoint = AMQP.newEndpoint(CONFIG, HOSTNAME, PORT, EXCHANGE, ExchangeType.Direct, Serializer.Java, None, 100)
 *
 *   endpoint ! MessageConsumer(QUEUE, ROUTING_KEY, new Actor() {
 *     def receive: PartialFunction[Any, Unit] = {
 *       case Message(payload, _, _, _, _) => log.debug("Received message: %s", payload)
 *     }
 *   })
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

  // ====== MESSAGES =====
  class Message(val payload: AnyRef, val routingKey: String, val mandatory: Boolean, val immediate: Boolean, val properties: RabbitMQ.BasicProperties) {
    override def toString(): String = "Message[payload=" + payload + ", routingKey=" + routingKey + ", properties=" + properties + "]"     
  }
  object Message {
    def unapply(message: Message): Option[Tuple5[AnyRef, String, Boolean, Boolean, RabbitMQ.BasicProperties]] =
      Some((message.payload, message.routingKey, message.mandatory, message.immediate, message.properties))
    def apply(payload: AnyRef, routingKey: String, mandatory: Boolean, immediate: Boolean, properties: RabbitMQ.BasicProperties): Message =
      new Message(payload, routingKey, mandatory, immediate, properties)
    def apply(payload: AnyRef, routingKey: String): Message =
      new Message(payload, routingKey, false, false, null)
  }

  case class MessageConsumer(queueName: String, routingKey: String, actor: Actor) {
    var tag: Option[String] = None

    override def toString(): String = "MessageConsumer[actor=" + actor + ", queue=" + queueName + ", routingKey=" + routingKey  + "]" 

    override def hashCode(): Int = synchronized {
      var result = HashCode.SEED
      result = HashCode.hash(result, queueName)
      result = HashCode.hash(result, routingKey)
      result
    }

    override def equals(that: Any): Boolean = synchronized {
      that != null &&
      that.isInstanceOf[MessageConsumer] &&
      that.asInstanceOf[MessageConsumer].queueName== queueName &&
      that.asInstanceOf[MessageConsumer].routingKey == routingKey
    }
  }

  case class CancelMessageConsumer(consumer: MessageConsumer)
  case class Reconnect(delay: Long)
  case class Failure(cause: Throwable)
  case object Stop
  // ===================

  class MessageNotDeliveredException(
          val message: String,
          val replyCode: Int,
          val replyText: String,
          val exchange: String,
          val routingKey: String,
          val properties: RabbitMQ.BasicProperties,
          val body: Array[Byte]) extends RuntimeException(message)

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
          exchangeType: ExchangeType,
          serializer: Serializer,
          shutdownListener: Option[ShutdownListener],
          initReconnectDelay: Long): Endpoint = {
    val endpoint = new Endpoint(
      new ConnectionFactory(config),
      hostname, port,
      exchangeName,
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
          val exchangeName: String,
          val serializer: Serializer,
          val returnListener: Option[ReturnListener],
          val shutdownListener: Option[ShutdownListener],
          val initReconnectDelay: Long)
    extends FaultTolerantConnectionActor {

    setupChannel
    
    log.info("AMQP.Client [%s] is started", toString)

    def receive: PartialFunction[Any, Unit] = {
      case message @ Message(payload, routingKey, mandatory, immediate, properties) =>
        log.debug("Sending message [%s]", message)
        channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties, serializer.out(payload))
      case Stop =>
        disconnect; stop
    }

    def setupChannel = {
      connection = connectionFactory.newConnection(hostname, port)
      channel = connection.createChannel
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
              "Could not deliver message [" + body +
                      "] with reply code [" + replyCode +
                      "] with reply text [" + replyText +
                      "] and routing key [" + routingKey +
                      "] to exchange [" + exchange + "]",
              replyCode, replyText, exchange, routingKey, properties, body)
          }
        })
      }
      if (shutdownListener.isDefined) connection.addShutdownListener(shutdownListener.get)      
    }

    override def toString(): String = "AMQP.Client[hostname=" + hostname + ", port=" + port + ", exchange=" + exchangeName + "]" 
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class Endpoint private[amqp] (
          val connectionFactory: ConnectionFactory,
          val hostname: String,
          val port: Int,
          exchangeName: String,
          exchangeType: ExchangeType,
          serializer: Serializer,
          shutdownListener: Option[ShutdownListener],
          val initReconnectDelay: Long)
    extends FaultTolerantConnectionActor {

    faultHandler = Some(OneForOneStrategy(5, 5000))
    trapExit = true

    val consumers = new HashMap[MessageConsumer, MessageConsumer]  
    val endpoint = this

    setupChannel

    log.info("AMQP.Endpoint [%s] is started", toString)

    def setupChannel = {
      connection = connectionFactory.newConnection(hostname, port)
      channel = connection.createChannel
      channel.exchangeDeclare(exchangeName, exchangeType.toString)
      consumers.elements.toList.map(_._2).foreach(setupConsumer) 
      if (shutdownListener.isDefined) connection.addShutdownListener(shutdownListener.get)
    }

    def setupConsumer(consumer: MessageConsumer) = {
      channel.queueDeclare(consumer.queueName)
      channel.queueBind(consumer.queueName, exchangeName, consumer.routingKey)

      val consumerTag = channel.basicConsume(consumer.queueName, false, new DefaultConsumer(channel) with Logging {
        override def handleDelivery(tag: String, envelope: Envelope, properties: RabbitMQ.BasicProperties, payload: Array[Byte]) {
          try {
            consumer.actor ! Message(serializer.in(payload, None), envelope.getRoutingKey)
            channel.basicAck(envelope.getDeliveryTag, false)
          } catch {
            case cause => endpoint ! Failure(cause) // pass on and rethrow exception in endpoint actor to trigger restart and reconnect
          }
        }        

        override def handleShutdownSignal(consumerTag: String, signal: ShutdownSignalException) = {
          consumers.elements.toList.map(_._2).find(_.tag == consumerTag) match {
            case None => log.warning("Could not find message consumer for tag [%s]; can't shut consumer down", consumerTag)
            case Some(consumer) =>
              log.warning("Message consumer [%s] is being shutdown by [%s] due to [%s]", consumer, signal.getReference, signal.getReason)
              endpoint ! CancelMessageConsumer(consumer)
          }
        }
      })
      consumer.tag = Some(consumerTag)
    }
    
    def receive: PartialFunction[Any, Unit] = {
      case consumer: MessageConsumer =>
        startLink(consumer.actor)
        consumers.put(consumer, consumer)
        setupConsumer(consumer)
        log.info("Message consumer is registered [%s]", consumer)

      case CancelMessageConsumer(hash) =>
        consumers.get(hash) match {
          case None => log.warning("Can't unregister message consumer [%s]; no such consumer", hash)
          case Some(consumer) =>
            consumers - consumer
            consumer.tag match {
              case None => log.warning("Can't unregister message consumer [%s]; no consumer tag", consumer)
              case Some(tag) =>
                channel.basicCancel(tag)
                unlink(consumer.actor)
                consumer.actor.stop
                log.info("Message consumer is cancelled and shut down [%s]", consumer)
            }
        }

      case Reconnect(delay) => reconnect(delay)
      case Failure(cause) => log.error(cause, ""); throw cause
      case Stop => disconnect; stop
      case unknown => throw new IllegalArgumentException("Unknown message [" + unknown + "] to AMQP Endpoint [" + this + "]")
    }

    override def toString(): String = "AMQP.Endpoint[hostname=" + hostname + ", port=" + port + ", exchange=" + exchangeName + ", type=" + exchangeType + "]"
  }

  trait FaultTolerantConnectionActor extends Actor {
    lifeCycleConfig = Some(LifeCycle(Permanent, 100))

    val reconnectionTimer = new Timer

    var connection: Connection = _
    var channel: Channel = _

    val connectionFactory: ConnectionFactory
    val hostname: String
    val port: Int
    val initReconnectDelay: Long

    def setupChannel

    protected def disconnect = {
      try {
        channel.close
      } catch {
        case e: IOException => log.error("Could not close AMQP channel %s:%s [%s]", hostname, port, this)
        case _ => ()
      }
      try {
        connection.close
        log.debug("Disconnected AMQP connection at %s:%s [%s]", hostname, port, this)
      } catch {
        case e: IOException => log.error("Could not close AMQP connection %s:%s [%s]", hostname, port, this)
        case _ => ()
      }
    }

    protected def reconnect(delay: Long) = {
      disconnect
      try {
        setupChannel
        log.debug("Successfully reconnected to AMQP Server %s:%s [%s]", hostname, port, this)
      } catch {
        case e: Exception =>
          val waitInMillis = delay * 2
          val self = this
          log.debug("Trying to reconnect to AMQP server in %n milliseconds [%s]", waitInMillis, this)
          reconnectionTimer.schedule(new TimerTask() {
            override def run = self ! Reconnect(waitInMillis)
          }, delay)
      }
    }

    override def preRestart(reason: AnyRef, config: Option[AnyRef]) = disconnect
    override def postRestart(reason: AnyRef, config: Option[AnyRef]) = reconnect(initReconnectDelay)
  }

  def receive: PartialFunction[Any, Unit] = {
    case _ => {} // ignore all messages
  }
}
