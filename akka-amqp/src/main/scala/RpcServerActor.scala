/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{ActorRef, Actor}
import com.rabbitmq.client.AMQP.BasicProperties
import se.scalablesolutions.akka.serialization.Serializer

class RpcServerActor(producer: ActorRef, inSerializer: Serializer, outSerializer: Serializer, requestHandler: PartialFunction[AnyRef, AnyRef]) extends Actor {

  log.info("%s started", this)

  protected def receive = {
    case Delivery(payload, _, tag, props, sender) => {

      log.debug("%s handling delivery with tag %d", this, tag)
      val request = inSerializer.fromBinary(payload, None)
      val response: Array[Byte] =  outSerializer.toBinary(requestHandler(request))

      log.info("%s sending reply to %s", this, props.getReplyTo)
      val replyProps = new BasicProperties
      replyProps.setCorrelationId(props.getCorrelationId)
      producer ! new Message(response, props.getReplyTo, properties = Some(replyProps))

      sender.foreach(_ ! Acknowledge(tag))
    }
    case Acknowledged(tag) => log.debug("%s acknowledged delivery with tag %d", this, tag)
  }

  override def toString(): String =
    "AMQP.RpcServer[producerId=" + producer.id + "]"
}