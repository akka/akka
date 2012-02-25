/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import collection.JavaConversions

import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{ Channel, Envelope, DefaultConsumer }
import akka.util.NonFatal

private[amqp] class ConsumerActor(consumerParameters: ConsumerParameters)
  extends FaultTolerantChannelActor(
    consumerParameters.exchangeParameters, consumerParameters.channelParameters) {
  import consumerParameters._

  var listenerTag: Option[String] = None

  def specificMessageHandler = {
    case Acknowledge(deliveryTag)     ⇒ acknowledgeDeliveryTag(deliveryTag, true)
    case Reject(deliveryTag, requeue) ⇒ rejectDeliveryTag(deliveryTag, requeue, true)
    case message: Message ⇒
      throw new IllegalArgumentException("%s can't be used to send messages, ignoring message [%s]".format(this, message))
    case unknown ⇒
      throw new IllegalArgumentException("Unknown message [%s] to %s".format(unknown, this))
  }

  protected def setupChannel(ch: Channel) = {

    channelParameters.foreach(params ⇒ ch.basicQos(params.prefetchSize))

    val exchangeName = exchangeParameters.flatMap(params ⇒ Some(params.exchangeName))
    val consumingQueue = exchangeName match {
      case Some(exchange) ⇒
        val queueDeclare: com.rabbitmq.client.AMQP.Queue.DeclareOk = {
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
        // no exchange, use routing key as queuename
        declareQueue(ch, routingKey, queueDeclaration)
        routingKey
    }

    val tag = ch.basicConsume(consumingQueue, false, new DefaultConsumer(ch) {
      override def handleDelivery(tag: String, envelope: Envelope, properties: BasicProperties, payload: Array[Byte]) {
        try {
          import envelope._
          val deliveryTag = getDeliveryTag
          deliveryHandler ! Delivery(payload, getRoutingKey, deliveryTag, isRedeliver, properties, Option(self))

          if (selfAcknowledging) {
            acknowledgeDeliveryTag(deliveryTag, false)
          }
        } catch {
          case NonFatal(cause) ⇒
            log.error(cause, "Delivery of message to %s failed" format toString)
            self ! Failure(cause) // pass on and re-throw exception in consumer actor to trigger restart and connect

        }
      }
    })
    listenerTag = Some(tag)
  }

  private def declareQueue(ch: Channel, queueName: String, queueDeclaration: Declaration): com.rabbitmq.client.AMQP.Queue.DeclareOk = {
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

  private def acknowledgeDeliveryTag(deliveryTag: Long, remoteAcknowledgement: Boolean): Unit =
    for (c ← channel) {
      c.basicAck(deliveryTag, false)
      if (remoteAcknowledgement) deliveryHandler ! Acknowledged(deliveryTag)
    }

  private def rejectDeliveryTag(deliveryTag: Long, requeue: Boolean, remoteAcknowledgement: Boolean) = {
    log.warning("Consumer is rejecting delivery with tag [{}] - requeue [{}]", deliveryTag, requeue)
    for (c ← channel) {
      c.basicReject(deliveryTag, requeue)
      if (remoteAcknowledgement) deliveryHandler ! Rejected(deliveryTag)
    }
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    super.preRestart(reason, message)
  }

  override def postStop = {
    for (tag ← listenerTag; ch ← channel if ch.isOpen) ch basicCancel tag

    // also stop the delivery handler
    context.stop(consumerParameters.deliveryHandler)

    super.postStop
  }

  override def toString =
    "AMQP.Consumer[actor path= " + self.path.toString +
      ", exchangeParameters=" + exchangeParameters +
      ", queueDeclaration=" + queueDeclaration + "]"
}

