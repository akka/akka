/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import com.rabbitmq.client._

import com.rabbitmq.client.AMQP.BasicProperties

private[amqp] class ProducerActor(producerParameters: ProducerParameters)
  extends FaultTolerantChannelActor(
    producerParameters.exchangeParameters, producerParameters.channelParameters) {

  import producerParameters._

  val exchangeName = exchangeParameters.flatMap(params ⇒ Some(params.exchangeName))

  def specificMessageHandler = {

    case message @ Message(payload, routingKey, mandatory, immediate, properties) if channel.isDefined ⇒ {
      log.debug("in producer, received message and channel is defined")
      channel.foreach(_.basicPublish(exchangeName.getOrElse(""), routingKey, mandatory, immediate, properties.getOrElse(null), payload))
    }
    case message @ Message(payload, routingKey, mandatory, immediate, properties) ⇒ {
      log.debug("in producer, received message and channel is not defined")
      errorCallbackActor match {
        case Some(errorCallbackActor) ⇒ errorCallbackActor ! message
        case None                     ⇒ log.warning("Unable to send message [%s]" format message)
      }
    }
  }

  protected def setupChannel(ch: Channel) {
    returnListener match {
      case Some(listener) ⇒ ch.addReturnListener(listener)
      case None ⇒ ch.addReturnListener(new ReturnListener() {
        def handleReturn(
          replyCode: Int,
          replyText: String,
          exchange: String,
          routingKey: String,
          properties: BasicProperties,
          body: Array[Byte]) {
          throw new MessageNotDeliveredException(
            "Could not deliver message [" + body +
              "] with reply code [" + replyCode +
              "] with reply text [" + replyText +
              "] and routing key [" + routingKey +
              "] to exchange [" + exchange + "]")
        }

      })
    }
  }

  override def toString =
    "AMQP.Producer[actor path= " + self.path.toString +
      ", exchangeParameters=" + exchangeParameters + "]"
}

