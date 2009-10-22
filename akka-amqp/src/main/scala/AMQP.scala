/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import com.rabbitmq.client.{AMQP => RabbitMQ, _}
import com.rabbitmq.client.ConnectionFactory

import se.scalablesolutions.akka.actor.{OneForOneStrategy, Actor}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.{HashCode, Logging}
import se.scalablesolutions.akka.serialization.Serializer

import scala.collection.mutable.HashMap

import org.scala_tools.javautils.Imports._

import java.util.concurrent.ConcurrentHashMap
import java.util.{Timer, TimerTask}
import java.io.IOException

/**
 * AMQP Actor API. Implements Producer and Consumer materialized as Actors.
 *
 * <pre>
 *   val params = new ConnectionParameters
 *   params.setUsername("barack")
 *   params.setPassword("obama")
 *   params.setVirtualHost("/")
 *   params.setRequestedHeartbeat(0)

 *   val consumer = AMQP.newConsumer(params, hostname, port, exchange, ExchangeType.Direct, Serializer.ScalaJSON, None, 100)
 *
 *   consumer ! MessageConsumerListener(queue, routingKey, new Actor() {
 *     def receive: PartialFunction[Any, Unit] = {
 *       case Message(payload, _, _, _, _) => log.debug("Received message: %s", payload)
 *     }
 *   })
 *
 *   val producer = AMQP.newProducer(params, hostname, port, exchange, Serializer.ScalaJSON, None, None, 100)
 *   producer ! Message("Hi", routingKey)
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

  case class MessageConsumerListener(queueName: String, routingKey: String, actor: Actor) {
    var tag: Option[String] = None

    override def toString(): String = "MessageConsumerListener[actor=" + actor + ", queue=" + queueName + ", routingKey=" + routingKey  + "]"

    override def hashCode(): Int = synchronized {
      var result = HashCode.SEED
      result = HashCode.hash(result, queueName)
      result = HashCode.hash(result, routingKey)
      result
    }

    override def equals(that: Any): Boolean = synchronized {
      that != null &&
      that.isInstanceOf[MessageConsumerListener] &&
      that.asInstanceOf[MessageConsumerListener].queueName== queueName &&
      that.asInstanceOf[MessageConsumerListener].routingKey == routingKey
    }
  }

  case class CancelMessageConsumerListener(consumer: MessageConsumerListener)
  case class Reconnect(delay: Long)
  case class Failure(cause: Throwable)
  case object Stop
 
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

  def newProducer(
          config: ConnectionParameters,
          hostname: String,
          port: Int,
          exchangeName: String,
          serializer: Serializer,
          returnListener: Option[ReturnListener],
          shutdownListener: Option[ShutdownListener],
          initReconnectDelay: Long): Producer = {
    val producer = new Producer(
      new ConnectionFactory(config),
      hostname, port,
      exchangeName,
      serializer,
      returnListener,
      shutdownListener,
      initReconnectDelay)
    startLink(producer)
    producer
  }

  def newConsumer(
          config: ConnectionParameters,
          hostname: String,
          port: Int,
          exchangeName: String,
          exchangeType: ExchangeType,
          serializer: Serializer,
          shutdownListener: Option[ShutdownListener],
          initReconnectDelay: Long,
          passive: Boolean,
          durable: Boolean,
          configurationArguments: Map[String, AnyRef]): Consumer = {
    val endpoint = new Consumer(
      new ConnectionFactory(config),
      hostname, port,
      exchangeName,
      exchangeType,
      serializer,
      shutdownListener,
      initReconnectDelay,
      passive,
      durable,
      configurationArguments)
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
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class Producer private[amqp] (
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

    log.info("AMQP.Producer [%s] is started", toString)

    def receive: PartialFunction[Any, Unit] = {
      case message @ Message(payload, routingKey, mandatory, immediate, properties) =>
        log.debug("Sending message [%s]", message)
        channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties, serializer.out(payload))
      case Stop =>
        disconnect
        stop
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

    override def toString(): String =
      "AMQP.Producer[hostname=" + hostname +
      ", port=" + port +
      ", exchange=" + exchangeName + "]"
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class Consumer private[amqp] (
          val connectionFactory: ConnectionFactory,
          val hostname: String,
          val port: Int,
          val exchangeName: String,
          val exchangeType: ExchangeType,
          val serializer: Serializer,
          val shutdownListener: Option[ShutdownListener],
          val initReconnectDelay: Long,
          val passive: Boolean,
          val durable: Boolean,
          val configurationArguments: Map[java.lang.String, Object])
    extends FaultTolerantConnectionActor { self: Consumer =>

    faultHandler = Some(OneForOneStrategy(5, 5000))
    trapExit = true

    val listeners = new HashMap[MessageConsumerListener, MessageConsumerListener]

    setupChannel

    log.info("AMQP.Consumer [%s] is started", toString)

    def setupChannel = {
      connection = connectionFactory.newConnection(hostname, port)
      channel = connection.createChannel
      channel.exchangeDeclare(exchangeName.toString, exchangeType.toString,
                              passive, durable,
                              configurationArguments.asJava)
      listeners.elements.toList.map(_._2).foreach(setupConsumer)
      if (shutdownListener.isDefined) connection.addShutdownListener(shutdownListener.get)
    }

    def setupConsumer(listener: MessageConsumerListener) = {
      channel.queueDeclare(listener.queueName)
      channel.queueBind(listener.queueName, exchangeName, listener.routingKey)

      val listenerTag = channel.basicConsume(listener.queueName, false, new DefaultConsumer(channel) with Logging {
        override def handleDelivery(tag: String, envelope: Envelope, properties: RabbitMQ.BasicProperties, payload: Array[Byte]) {
          try {
            listener.actor ! Message(serializer.in(payload, None), envelope.getRoutingKey)
            channel.basicAck(envelope.getDeliveryTag, false)
          } catch {
            case cause => self ! Failure(cause) // pass on and re-throw exception in endpoint actor to trigger restart and reconnect
          }
        }

        override def handleShutdownSignal(listenerTag: String, signal: ShutdownSignalException) = {
          listeners.elements.toList.map(_._2).find(_.tag == listenerTag) match {
            case None => log.warning("Could not find message listener for tag [%s]; can't shut listener down", listenerTag)
            case Some(listener) =>
              log.warning("Message listener listener [%s] is being shutdown by [%s] due to [%s]", listener, signal.getReference, signal.getReason)
              self ! CancelMessageConsumerListener(listener)
          }
        }
      })
      listener.tag = Some(listenerTag)
    }

    def receive: PartialFunction[Any, Unit] = {
      case listener: MessageConsumerListener =>
        startLink(listener.actor)
        listeners.put(listener, listener)
        setupConsumer(listener)
        log.info("Message consumer listener is registered [%s]", listener)

      case CancelMessageConsumerListener(hash) =>
        listeners.get(hash) match {
          case None => log.warning("Can't unregister message consumer listener [%s]; no such listener", hash)
          case Some(listener) =>
            listeners - listener
            listener.tag match {
              case None => log.warning("Can't unregister message consumer listener [%s]; no listener tag", listener)
              case Some(tag) =>
                channel.basicCancel(tag)
                unlink(listener.actor)
                listener.actor.stop
                log.info("Message consumer is cancelled and shut down [%s]", listener)
            }
        }

      case Reconnect(delay) => reconnect(delay)
      case Failure(cause) => log.error(cause, ""); throw cause
      case Stop => disconnect; stop
      case unknown => throw new IllegalArgumentException("Unknown message [" + unknown + "] to AMQP Consumer [" + this + "]")
    }

    override def toString(): String =
      "AMQP.Consumer[hostname=" + hostname +
      ", port=" + port +
      ", exchange=" + exchangeName +
      ", type=" + exchangeType +
      ", passive=" + passive +
      ", durable=" + durable + "]"
  }

  trait FaultTolerantConnectionActor extends Actor {
    lifeCycleConfig = Some(LifeCycle(Permanent, 100))

    val reconnectionTimer = new Timer

    var connection: Connection = _
    var channel: Channel = _

    val hostname: String
    val port: Int
    val initReconnectDelay: Long
    val exchangeName: String
    val connectionFactory: ConnectionFactory

    def setupChannel

    def createQueue: String = channel.queueDeclare.getQueue

    def createQueue(name: String) { channel.queueDeclare(name) }

    def createQueue(name: String, durable: Boolean) { channel.queueDeclare(name, durable) }

    def createBindQueue: String = { 
      val name = channel.queueDeclare.getQueue
      channel.queueBind(name, exchangeName, name)
      name
    }

    def createBindQueue(name: String) { 
      channel.queueDeclare(name)
      channel.queueBind(name, exchangeName, name)
    }

    def createBindQueue(name: String, durable: Boolean) { 
      channel.queueDeclare(name, durable)
      channel.queueBind(name, exchangeName, name)
    }

    def deleteQueue(name: String) { channel.queueDelete(name) }

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
