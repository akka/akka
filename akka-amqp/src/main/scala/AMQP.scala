/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.config.OneForOneStrategy
import com.rabbitmq.client.{ReturnListener, ShutdownListener, ConnectionFactory}
import java.lang.IllegalArgumentException
import se.scalablesolutions.akka.util.{Logging}
import java.util.UUID

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

  case class ProducerParameters(exchangeParameters: ExchangeParameters,
                                producerId: Option[String] = None,
                                returnListener: Option[ReturnListener] = None,
                                channelParameters: Option[ChannelParameters] = None)

  case class ConsumerParameters(exchangeParameters: ExchangeParameters,
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
    val connection: ActorRef = supervisor.newConnection(connectionParameters)
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
    consumer.startLink(consumerParameters.deliveryHandler)
    connection.startLink(consumer)
    consumer ! Start
    consumer
  }

  def newRpcClient(connection: ActorRef,
                   exchangeParameters: ExchangeParameters,
                   routingKey: String,
                   deliveryHandler: ActorRef,
                   channelParameters: Option[ChannelParameters] = None): ActorRef = {
    val replyToRoutingKey = UUID.randomUUID.toString
    val producer = newProducer(connection, new ProducerParameters(exchangeParameters, channelParameters = channelParameters))
    val consumer = newConsumer(connection, new ConsumerParameters(exchangeParameters, replyToRoutingKey, deliveryHandler, channelParameters = channelParameters))
    val rpcActor: ActorRef = actorOf(new RpcClientActor(producer, routingKey, replyToRoutingKey)).start
    rpcActor
  }

  def newRpcServer(connection: ActorRef,
          exchangeParameters: ExchangeParameters,
          routingKey: String,
          requestHandler: Function[Array[Byte], Array[Byte]],
          channelParameters: Option[ChannelParameters] = None) = {
    val producer = newProducer(connection, new ProducerParameters(exchangeParameters, channelParameters = channelParameters))
    val rpcServer: ActorRef = actorOf(new RpcServerActor(producer, requestHandler)).start
    val consumer = newConsumer(connection, new ConsumerParameters(exchangeParameters, routingKey, rpcServer
      , channelParameters = channelParameters
      , selfAcknowledging = false))

  }

  private val supervisor = new AMQPSupervisor

  class AMQPSupervisor extends Logging {
    class AMQPSupervisorActor extends Actor {
      import self._

      faultHandler = Some(OneForOneStrategy(5, 5000))
      trapExit = List(classOf[Throwable])

      def receive = {
        case _ => {} // ignore all messages
      }
    }

    private val supervisor = actorOf(new AMQPSupervisorActor).start

    def newConnection(connectionParameters: ConnectionParameters): ActorRef = {
      val connectionActor = actorOf(new FaultTolerantConnectionActor(connectionParameters))
      supervisor.startLink(connectionActor)
      connectionActor
    }
  }
}
