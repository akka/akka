/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.config.OneForOneStrategy
import com.rabbitmq.client.{ReturnListener, ShutdownListener, ConnectionFactory}
import com.rabbitmq.client.AMQP.BasicProperties
import java.lang.{String, IllegalArgumentException}

/**
 * AMQP Actor API. Implements Connection, Producer and Consumer materialized as Actors.
 *
 * @see se.scalablesolutions.akka.amqp.ExampleSession
 *
 * @author Irmo Manie
 */
object AMQP {
  case class ConnectionParameters(
          host: String = ConnectionFactory.DEFAULT_HOST,
          port: Int = ConnectionFactory.DEFAULT_AMQP_PORT,
          username: String = ConnectionFactory.DEFAULT_USER,
          password: String = ConnectionFactory.DEFAULT_PASS,
          virtualHost: String = ConnectionFactory.DEFAULT_VHOST,
          initReconnectDelay: Long = 5000,
          connectionCallback: Option[ActorRef] = None)

  case class ChannelParameters(
          shutdownListener: Option[ShutdownListener] = None,
          channelCallback: Option[ActorRef] = None)

  case class ExchangeParameters(
          exchangeName: String,
          exchangeType: ExchangeType,
          exchangeDurable: Boolean = false,
          exchangeAutoDelete: Boolean = true,
          exchangePassive: Boolean = false,
          configurationArguments: Map[String, AnyRef] = Map())

  case class ProducerParameters(
          exchangeParameters: ExchangeParameters,
          producerId: Option[String] = None,
          returnListener: Option[ReturnListener] = None,
          channelParameters: Option[ChannelParameters] = None)

  case class ConsumerParameters(
          exchangeParameters: ExchangeParameters,
          routingKey: String,
          deliveryHandler: ActorRef,
          queueName: Option[String] = None,
          queueDurable: Boolean = false,
          queueAutoDelete: Boolean = true,
          queuePassive: Boolean = false,
          queueExclusive: Boolean = false,
          selfAcknowledging: Boolean = true,
          channelParameters: Option[ChannelParameters] = None) {
    if (queueDurable && queueName.isEmpty) {
      throw new IllegalArgumentException("A queue name is required when requesting a durable queue.")
    }
  }

  def newConnection(connectionParameters: ConnectionParameters = new ConnectionParameters): ActorRef = {
    val connection = actorOf(new FaultTolerantConnectionActor(connectionParameters))
    supervisor.startLink(connection)
    connection ! Connect
    connection
  }

  def newProducer(connection: ActorRef, producerParameters: ProducerParameters): ActorRef = {
    val producer: ActorRef = Actor.actorOf(new ProducerActor(producerParameters))
    connection.startLink(producer)
    producer ! Start
    producer
  }

  def newConsumer(connection: ActorRef, consumerParameters: ConsumerParameters): ActorRef = {
    val consumer: ActorRef = actorOf(new ConsumerActor(consumerParameters))
    val handler = consumerParameters.deliveryHandler
    if (handler.supervisor.isEmpty) consumer.startLink(handler)
    connection.startLink(consumer)
    consumer ! Start
    consumer
  }

  /**
   * Convenience
   */
  class ProducerClient[O](client: ActorRef, routingKey: String, toBinary: ToBinary[O]) {
    def send(request: O, replyTo: Option[String] = None) = {
      val basicProperties = new BasicProperties
      basicProperties.setReplyTo(replyTo.getOrElse(null))
      client ! Message(toBinary.toBinary(request), routingKey, false, false, Some(basicProperties))
    }

    def stop = client.stop
  }

  def newStringProducer(connection: ActorRef,
                          exchange: String,
                          routingKey: Option[String] = None,
                          producerId: Option[String] = None,
                          durable: Boolean = false,
                          autoDelete: Boolean = true,
                          passive: Boolean = true): ProducerClient[String] = {

    val exchangeParameters = ExchangeParameters(exchange, ExchangeType.Topic,
      exchangeDurable = durable, exchangeAutoDelete = autoDelete)
    val rKey = routingKey.getOrElse("%s.request".format(exchange))

    val producerRef = newProducer(connection, ProducerParameters(exchangeParameters, producerId))
    val toBinary = new ToBinary[String] {
      def toBinary(t: String) = t.getBytes
    }
    new ProducerClient(producerRef, rKey, toBinary)
  }

  def newStringConsumer(connection: ActorRef,
                          exchange: String,
                          handler: String => Unit,
                          routingKey: Option[String] = None,
                          queueName: Option[String] = None,
                          durable: Boolean = false,
                          autoDelete: Boolean = true): ActorRef = {

    val deliveryHandler = actor {
      case Delivery(payload, _, _, _, _) => handler.apply(new String(payload))
    }

    val exchangeParameters = ExchangeParameters(exchange, ExchangeType.Topic,
      exchangeDurable = durable, exchangeAutoDelete = autoDelete)
    val rKey = routingKey.getOrElse("%s.request".format(exchange))
    val qName = queueName.getOrElse("%s.in".format(rKey))

    newConsumer(connection, ConsumerParameters(exchangeParameters, rKey, deliveryHandler, Some(qName), durable, autoDelete))
  }

  def newProtobufProducer[O <: com.google.protobuf.Message](connection: ActorRef,
                                        exchange: String,
                                        routingKey: Option[String] = None,
                                        producerId: Option[String] = None,
                                        durable: Boolean = false,
                                        autoDelete: Boolean = true,
                                        passive: Boolean = true): ProducerClient[O] = {

    val exchangeParameters = ExchangeParameters(exchange, ExchangeType.Topic,
      exchangeDurable = durable, exchangeAutoDelete = autoDelete)
    val rKey = routingKey.getOrElse("%s.request".format(exchange))

    val producerRef = newProducer(connection, ProducerParameters(exchangeParameters, producerId))
    new ProducerClient(producerRef, rKey, new ToBinary[O] {
      def toBinary(t: O) = t.toByteArray
    })
  }

  def newProtobufConsumer[I <: com.google.protobuf.Message](connection: ActorRef,
                          exchange: String,
                          handler: I => Unit,
                          routingKey: Option[String] = None,
                          queueName: Option[String] = None,
                          durable: Boolean = false,
                          autoDelete: Boolean = true)(implicit manifest: Manifest[I]): ActorRef = {

    val deliveryHandler = actor {
      case Delivery(payload, _, _, _, _) => {
        handler.apply(createProtobufFromBytes[I](payload))
      }
    }

    val exchangeParameters = ExchangeParameters(exchange, ExchangeType.Topic,
      exchangeDurable = durable, exchangeAutoDelete = autoDelete)
    val rKey = routingKey.getOrElse("%s.request".format(exchange))
    val qName = queueName.getOrElse("%s.in".format(rKey))

    newConsumer(connection, ConsumerParameters(exchangeParameters, rKey, deliveryHandler, Some(qName), durable, autoDelete))
  }

  /**
   * Main supervisor
   */

  class AMQPSupervisorActor extends Actor {
    import self._

    faultHandler = Some(OneForOneStrategy(5, 5000))
    trapExit = List(classOf[Throwable])

    def receive = {
      case _ => {} // ignore all messages
    }
  }

  private val supervisor = actorOf(new AMQPSupervisorActor).start

  def shutdownAll = {
    supervisor.shutdownLinkedActors
  }

  /**
   * Serialization stuff
   */

  trait FromBinary[T] {
    def fromBinary(bytes: Array[Byte]): T
  }

  trait ToBinary[T] {
    def toBinary(t: T): Array[Byte]
  }

  private val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])

  private[amqp] def createProtobufFromBytes[I <: com.google.protobuf.Message](bytes: Array[Byte])(implicit manifest: Manifest[I]): I = {
    manifest.erasure.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[I]
  }
}
