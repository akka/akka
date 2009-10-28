/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import com.rabbitmq.client.{AMQP => RabbitMQ, _}
import com.rabbitmq.client.ConnectionFactory

import se.scalablesolutions.akka.actor.{OneForOneStrategy, Actor}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.{HashCode, Logging}

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

  sealed trait AMQPMessage
  private[akka] trait InternalAMQPMessage extends AMQPMessage

  class Message(val payload: Array[Byte],
                val routingKey: String,
                val mandatory: Boolean,
                val immediate: Boolean,
                val properties: RabbitMQ.BasicProperties) extends AMQPMessage {
    override def toString(): String =
      "Message[payload=" + payload +
      ", routingKey=" + routingKey +
      ", mandatory=" + mandatory +
      ", immediate=" + immediate +
      ", properties=" + properties + "]"
  }

  object Message {
    def unapply(message: Message): Option[Tuple5[AnyRef, String, Boolean, Boolean, RabbitMQ.BasicProperties]] =
      Some((message.payload, message.routingKey, message.mandatory, message.immediate, message.properties))

    def apply(payload: Array[Byte], routingKey: String, mandatory: Boolean, immediate: Boolean, properties: RabbitMQ.BasicProperties): Message =
      new Message(payload, routingKey, mandatory, immediate, properties)

    def apply(payload: Array[Byte], routingKey: String): Message =
      new Message(payload, routingKey, false, false, null)
  }

  case class MessageConsumerListener(queueName: String, 
                                     routingKey: String, 
                                     isUsingExistingQueue: Boolean, 
                                     actor: Actor) extends AMQPMessage {
    def this(queueName: String, routingKey: String, actor: Actor) = this(queueName, routingKey, false, actor)
    
    private[akka] var tag: Option[String] = None

    override def toString() = 
      "MessageConsumerListener[actor=" + actor +
      ", queue=" + queueName +
      ", routingKey=" + routingKey  +
      ", tag=" + tag +
      ", isUsingExistingQueue=" + isUsingExistingQueue  + "]"

    def toString(exchangeName: String) = 
      "MessageConsumerListener[actor=" + actor +
      ", exchange=" + exchangeName +
      ", queue=" + queueName +
      ", routingKey=" + routingKey  +
      ", tag=" + tag +
      ", isUsingExistingQueue=" + isUsingExistingQueue  + "]"

    /**
     * Hash code should only be based on on queue name and routing key.
     */
    override def hashCode(): Int = synchronized {
      var result = HashCode.SEED
      result = HashCode.hash(result, queueName)
      result = HashCode.hash(result, routingKey)
      result
    }

    /**
     * Equality should only be defined in terms of queue name and routing key.
     */
    override def equals(that: Any): Boolean = synchronized {
      that != null &&
      that.isInstanceOf[MessageConsumerListener] &&
      that.asInstanceOf[MessageConsumerListener].queueName== queueName &&
      that.asInstanceOf[MessageConsumerListener].routingKey == routingKey
    }
  }
  object MessageConsumerListener {
    def apply(queueName: String, routingKey: String, actor: Actor) = new MessageConsumerListener(queueName, routingKey, false, actor)    
  }
  
  case object Stop extends AMQPMessage
  
  private[akka] case class UnregisterMessageConsumerListener(consumer: MessageConsumerListener) extends InternalAMQPMessage
  
  private[akka] case class Reconnect(delay: Long) extends InternalAMQPMessage
  
  private[akka] case class Failure(cause: Throwable) extends InternalAMQPMessage
 
  private[akka] class MessageNotDeliveredException(
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
          returnListener: Option[ReturnListener],
          shutdownListener: Option[ShutdownListener],
          initReconnectDelay: Long): Producer = {
    val producer = new Producer(
      new ConnectionFactory(config),
      hostname, port,
      exchangeName,
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
          shutdownListener: Option[ShutdownListener],
          initReconnectDelay: Long,
          passive: Boolean,
          durable: Boolean,
          configurationArguments: Map[String, AnyRef]): Consumer = {
    val consumer = new Consumer(
      new ConnectionFactory(config),
      hostname, port,
      exchangeName,
      exchangeType,
      shutdownListener,
      initReconnectDelay,
      passive,
      durable,
      configurationArguments)
    startLink(consumer)
    consumer
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
          val returnListener: Option[ReturnListener],
          val shutdownListener: Option[ShutdownListener],
          val initReconnectDelay: Long)
    extends FaultTolerantConnectionActor {

    setupChannel

    log.info("AMQP.Producer [%s] is started", toString)

    def newRpcClient(routingKey: String): RpcClient = new RpcClient(channel, exchangeName, routingKey)
    
    def receive = {
      case message @ Message(payload, routingKey, mandatory, immediate, properties) =>
        log.debug("Sending message [%s]", message)
        channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties, payload.asInstanceOf[Array[Byte]])
      case Stop =>
        disconnect
        stop
    }

    protected def setupChannel = {
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
          val shutdownListener: Option[ShutdownListener],
          val initReconnectDelay: Long,
          val passive: Boolean,
          val durable: Boolean,
          val configurationArguments: Map[java.lang.String, Object])
    extends FaultTolerantConnectionActor { self: Consumer =>

    faultHandler = Some(OneForOneStrategy(5, 5000))
    trapExit = true

    private val listeners = new HashMap[MessageConsumerListener, MessageConsumerListener]

    setupChannel

    log.info("AMQP.Consumer [%s] is started", toString)

    def newRpcServerWithCallback(body: (Array[Byte], RabbitMQ.BasicProperties) => Array[Byte]): RpcServer = {
      new RpcServer(channel) {
        override def handleCall(requestBody: Array[Byte], replyProperties: RabbitMQ.BasicProperties) = {
          body(requestBody, replyProperties)
        }
      }
    } 

    def receive = {
      case listener: MessageConsumerListener =>
        startLink(listener.actor)
        registerListener(listener)
        log.info("Message consumer listener is registered [%s]", listener)

      case UnregisterMessageConsumerListener(listener) =>
        unregisterListener(listener)
        
      case Reconnect(delay) => 
        reconnect(delay)

      case Failure(cause) => 
        log.error(cause, "")
        throw cause

      case Stop => 
        listeners.elements.toList.map(_._2).foreach(unregisterListener(_))
        disconnect
        stop

      case message: Message => 
        handleIllegalMessage("AMQP.Consumer [" + this + "] can't be used to send messages, ignoring message [" + message + "]")

      case unknown => 
        handleIllegalMessage("Unknown message [" + unknown + "] to AMQP Consumer [" + this + "]")
    }

    protected def setupChannel = {
      connection = connectionFactory.newConnection(hostname, port)
      channel = connection.createChannel
      channel.exchangeDeclare(exchangeName.toString, exchangeType.toString,
                              passive, durable,
                              configurationArguments.asJava)
      listeners.elements.toList.map(_._2).foreach(registerListener)
      if (shutdownListener.isDefined) connection.addShutdownListener(shutdownListener.get)
    }

    private def registerListener(listener: MessageConsumerListener) = {
      log.debug("Register MessageConsumerListener %s", listener.toString(exchangeName))
      listeners.put(listener, listener)

      if (!listener.isUsingExistingQueue) {
        log.debug("Declaring new queue for MessageConsumerListener [%s]", listener.queueName)
        channel.queueDeclare(listener.queueName)
      }

      log.debug("Binding new queue for MessageConsumerListener [%s]", listener.queueName)
      channel.queueBind(listener.queueName, exchangeName, listener.routingKey)        

      val listenerTag = channel.basicConsume(listener.queueName, true, new DefaultConsumer(channel) with Logging {
        override def handleDelivery(tag: String, 
                                    envelope: Envelope, 
                                    properties: RabbitMQ.BasicProperties, 
                                    payload: Array[Byte]) {
          try {
            val mandatory = false // FIXME: where to find out if it's mandatory?
            val immediate = false // FIXME: where to find out if it's immediate?
            log.debug("Passing a message on to the MessageConsumerListener [%s]", listener.toString(exchangeName))
            listener.actor ! Message(payload, envelope.getRoutingKey, mandatory, immediate, properties)
            val deliveryTag = envelope.getDeliveryTag
            log.debug("Acking message with delivery tag [%s]", deliveryTag)
            channel.basicAck(deliveryTag, false)
          } catch {
            case cause => 
              log.error("Delivery of message to MessageConsumerListener [%s] failed due to [%s]", listener.toString(exchangeName), cause.toString)
              self ! Failure(cause) // pass on and re-throw exception in consumer actor to trigger restart and reconnect
          }
        }

        override def handleShutdownSignal(listenerTag: String, signal: ShutdownSignalException) = {
          def hasTag(listener: MessageConsumerListener, listenerTag: String): Boolean = {
            if (listener.tag.isEmpty) throw new IllegalStateException("MessageConsumerListener [" + listener + "] does not have a tag")
            listener.tag.get == listenerTag
          }
          listeners.elements.toList.map(_._2).find(hasTag(_, listenerTag)) match {
            case None => log.error("Could not find message listener for tag [%s]; can't shut listener down", listenerTag)
            case Some(listener) =>
              log.warning("MessageConsumerListener [%s] is being shutdown by [%s] due to [%s]", 
                          listener.toString(exchangeName), signal.getReference, signal.getReason)
              self ! UnregisterMessageConsumerListener(listener)
          }
        }
      })
      listener.tag = Some(listenerTag)
    }

    private def unregisterListener(listener: MessageConsumerListener) = {
      listeners.get(listener) match {
        case None => log.warning("Can't unregister message consumer listener [%s]; no such listener", listener.toString(exchangeName))
        case Some(listener) =>
          listeners - listener
          listener.tag match {
            case None => log.warning("Can't unregister message consumer listener [%s]; no listener tag", listener.toString(exchangeName))
            case Some(tag) =>
              channel.basicCancel(tag)
              unlink(listener.actor)
              listener.actor.stop
              log.debug("Message consumer is cancelled and shut down [%s]", listener)
          }
      }
    }
   
    private def handleIllegalMessage(errorMessage: String) = {
      log.error(errorMessage)
      throw new IllegalArgumentException(errorMessage)
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
    lifeCycle = Some(LifeCycle(Permanent, 100))

    val reconnectionTimer = new Timer

    var connection: Connection = _
    var channel: Channel = _

    val hostname: String
    val port: Int
    val initReconnectDelay: Long
    val exchangeName: String
    val connectionFactory: ConnectionFactory

    protected def setupChannel

    def createQueue: String = channel.queueDeclare("", false, false, true, true, null).getQueue

    def createQueue(name: String) = channel.queueDeclare(name, false, false, true, true, null).getQueue

    def createQueue(name: String, durable: Boolean) = channel.queueDeclare(name, false, durable, true, true, null).getQueue

    def createBindQueue: String = { 
      val name = createQueue
      channel.queueBind(name, exchangeName, name)
      name
    }

    def createBindQueue(name: String) { 
      createQueue(name)
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

  def receive = {
    case _ => {} // ignore all messages
  }
}
