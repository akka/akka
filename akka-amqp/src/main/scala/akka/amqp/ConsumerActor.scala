/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import collection.JavaConversions

import akka.util.NonFatal
import com.rabbitmq.client.AMQP.{ Queue, BasicProperties }
import akka.actor.Status
import akka.dispatch.Future
import java.io.IOException
import com.rabbitmq.client.{ ShutdownSignalException, Channel, Envelope, DefaultConsumer }

private[amqp] class ConsumerActor(consumerParameters: ConsumerParameters)
  extends FaultTolerantChannelActor(
    consumerParameters.exchangeParameters, consumerParameters.channelParameters) {

  import consumerParameters._

  var listenerTag: Option[String] = None

  def specificMessageHandler = {
    /**
     * if we get a message acknowledgement request, and we have a channel, ack the message, otherwise, respond to the sender
     * with a failure, and try to start the channel.
     */
    case Acknowledge(deliveryTag) ⇒ channel match {
      case Some(cf) ⇒ for (c ← cf) acknowledgeDeliveryTag(c, deliveryTag, true)
      case None ⇒
        sender ! Status.Failure(new AkkaAMQPException("consumer " + self + " could not acknowledge message - channel not available"))
        self ! Start
    }
    /**
     * if we get a message reject request, and we have a channel, rejecxt the message, otherwise, respond to the sender
     * with a failure and try to start the channel.
     */
    case Reject(deliveryTag, requeue) ⇒ channel match {
      case Some(cf) ⇒ rejectDeliveryTag(cf, deliveryTag, requeue, true)
      case None ⇒
        sender ! Status.Failure(new AkkaAMQPException("consumer " + self + " could not reject message - channel not available"))
        self ! Start
    }
    /**
     * ignore any other kind of message.  control message for the channel will be handled by another block
     * in the FaultTolerantChannelActor
     */
    case _ ⇒ ()
  }

  /**
   * implements the consumer logic for setting up the channel.  set up the consuming queue and bind it to the channel.  if a return listener was defined in the producer params,
   * we use it to define what to do when publishing to the channel fails and the mandatory or immediate flags were set,
   * otherwise we create a generic one that recycles the channel.
   */
  protected def setupChannel(ch: Channel) = {

    if (channelParameters.isDefined) ch.basicQos(channelParameters.get.prefetchSize)

    val exchangeName = exchangeParameters.flatMap(params ⇒ Option(params.exchangeName))
    val consumingQueue = exchangeName match {
      case Some(exchange) ⇒
        val queueDeclare: Queue.DeclareOk = {
          queueName match {
            case Some(name) ⇒
              declareQueue(ch, name, queueDeclaration)
            case None ⇒
              ch.queueDeclare
          }
        }
        ch.queueBind(queueDeclare.getQueue, exchange, routingKey)
        queueDeclare.getQueue
      case None ⇒
        // no exchange, use routing key as queue name
        declareQueue(ch, routingKey, queueDeclaration)
        routingKey
    }

    listenerTag = {
      val replyTo = self
      Option(ch.basicConsume(consumingQueue, false, new DefaultConsumer(ch) {
        override def handleDelivery(tag: String, envelope: Envelope, properties: BasicProperties, payload: Array[Byte]) {
          import envelope._
          val deliveryTag = getDeliveryTag
          deliveryHandler ! Delivery(payload, getRoutingKey, deliveryTag, isRedeliver, properties, Option(replyTo))

          if (selfAcknowledging) {
            acknowledgeDeliveryTag(ch, deliveryTag, false)
          }
        }
      }))
    }
  }

  private def declareQueue(ch: Channel, queueName: String, queueDeclaration: Declaration): Queue.DeclareOk = {
    queueDeclaration match {
      case PassiveDeclaration ⇒
        ch.queueDeclarePassive(queueName)
      case ActiveDeclaration(durable, autoDelete, exclusive) ⇒
        val configurationArguments = exchangeParameters match {
          case Some(params) ⇒ params.configurationArguments
          case _            ⇒ Map[String, AnyRef]()
        }
        ch.queueDeclare(queueName, durable, exclusive, autoDelete, JavaConversions.mapAsJavaMap(configurationArguments.toMap))
      case NoActionDeclaration ⇒ new com.rabbitmq.client.impl.AMQImpl.Queue.DeclareOk(queueName, 0, 0) // do nothing here
    }
  }

  private def acknowledgeDeliveryTag(channel: Channel, deliveryTag: Long, remoteAcknowledgement: Boolean): Unit = {
    channel.basicAck(deliveryTag, false)
    if (remoteAcknowledgement) deliveryHandler ! Acknowledged(deliveryTag)
  }

  private def rejectDeliveryTag(channel: Future[Channel], deliveryTag: Long, requeue: Boolean, remoteAcknowledgement: Boolean) = {
    log.warning("Consumer is rejecting delivery with tag [{}] - requeue [{}]", deliveryTag, requeue)
    for (c ← channel) {
      c.basicReject(deliveryTag, requeue)
      if (remoteAcknowledgement) deliveryHandler ! Rejected(deliveryTag)
    }
  }

  /**
   * cancel the consumer, stop the delivery handler actor
   */
  override def postStop = {
    for (tag ← listenerTag; opt ← channel; ch ← opt if ch.isOpen)
      try { ch basicCancel tag }
      catch {
        case e: IOException ⇒
          e.getCause match {
            case s: ShutdownSignalException if s.isInitiatedByApplication ⇒ () // the connection has been shut down by us, ignore
            case _ ⇒ log.warning("Consumer got a wrapped IOException during postStop - " + e.getCause.getMessage)
          }
      }
    notifyCallback(Stopped)
  }

  override def toString =
    "AMQP.Consumer[actor= " + self +
      ", exchangeParameters=" + exchangeParameters +
      ", queueDeclaration=" + queueDeclaration + "]"

}

