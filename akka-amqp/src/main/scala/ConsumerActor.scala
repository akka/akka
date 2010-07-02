/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import com.rabbitmq.client.AMQP.Queue.DeclareOk
import collection.JavaConversions
import se.scalablesolutions.akka.amqp.AMQP.ConsumerParameters
import se.scalablesolutions.akka.util.Logging
import com.rabbitmq.client.{Channel, Envelope, DefaultConsumer}
import com.rabbitmq.client.AMQP.BasicProperties
import java.lang.Throwable

private[amqp] class ConsumerActor(consumerParameters: ConsumerParameters) extends FaultTolerantChannelActor(consumerParameters.channelParameters) {
  import consumerParameters._
  import channelParameters._

  var listenerTag: Option[String] = None

  def specificMessageHandler = {
    case Acknowledge(deliveryTag) => acknowledgeDeliveryTag(deliveryTag, true)
    case message: Message =>
      handleIllegalMessage("%s can't be used to send messages, ignoring message [%s]".format(this, message))
    case unknown =>
      handleIllegalMessage("Unknown message [%s] to %s".format(unknown, this))
  }

  protected def setupChannel(ch: Channel) = {

    val queueDeclare: DeclareOk = {
      queueName match {
        case Some(name) =>
          log.debug("Declaring new queue [%s] for %s", name, toString)
          if (queuePassive) {
            ch.queueDeclarePassive(name)
          } else {
            ch.queueDeclare(name, queueDurable, queueExclusive, queueAutoDelete, JavaConversions.asMap(configurationArguments))
          }
        case None =>
          log.debug("Declaring new generated queue for %s", toString)
          ch.queueDeclare
      }
    }

    log.debug("Binding new queue [%s] for %s", queueDeclare.getQueue, toString)
    ch.queueBind(queueDeclare.getQueue, exchangeName, routingKey)

    val tag = ch.basicConsume(queueDeclare.getQueue, false, new DefaultConsumer(ch) with Logging {
      override def handleDelivery(tag: String, envelope: Envelope, properties: BasicProperties, payload: Array[Byte]) {
        try {
          val deliveryTag = envelope.getDeliveryTag
          log.debug("Passing a message on to %s", toString)
          deliveryHandler ! Delivery(payload, envelope.getRoutingKey, envelope.getDeliveryTag, properties, someSelf)

          if (selfAcknowledging) {
            log.debug("Self acking...")
            acknowledgeDeliveryTag(deliveryTag, false)
          }
        } catch {
          case cause =>
            log.error(cause, "Delivery of message to %s failed", toString)
            self ! Failure(cause) // pass on and re-throw exception in consumer actor to trigger restart and connect
        }
      }
    })
    listenerTag = Some(tag)
    log.info("Intitialized %s", toString)
  }

  private def acknowledgeDeliveryTag(deliveryTag: Long, remoteAcknowledgement: Boolean) = {
    log.debug("Acking message with delivery tag [%s]", deliveryTag)
    channel.foreach{ch =>
      ch.basicAck(deliveryTag, false)
      if (remoteAcknowledgement) {
        deliveryHandler ! Acknowledged(deliveryTag)
      }
    }
  }

  private def handleIllegalMessage(errorMessage: String) = {
    log.error(errorMessage)
    throw new IllegalArgumentException(errorMessage)
  }


  override def preRestart(reason: Throwable) = {
    listenerTag = None
    super.preRestart(reason)
  }

  override def shutdown = {
    listenerTag.foreach(tag => channel.foreach(_.basicCancel(tag)))
    self.linkedActorsAsList.foreach(_.stop)
    super.shutdown
  }

  override def toString(): String =
    "AMQP.Consumer[id= "+ self.id +
            ", exchange=" + exchangeName +
            ", exchangeType=" + exchangeType +
            ", durable=" + exchangeDurable +
            ", autoDelete=" + exchangeAutoDelete + "]"
}

