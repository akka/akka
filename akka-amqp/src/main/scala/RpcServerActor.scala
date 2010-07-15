/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{ActorRef, Actor}
import com.rabbitmq.client.AMQP.BasicProperties
import se.scalablesolutions.akka.amqp.AMQP.RpcServerSerializer

class RpcServerActor[I,O](producer: ActorRef, serializer: RpcServerSerializer[I,O], requestHandler: PartialFunction[I, O]) extends Actor {

  log.info("%s started", this)

  protected def receive = {
    case Delivery(payload, _, tag, props, sender) => {

      log.debug("%s handling delivery with tag %d", this, tag)
      val request = serializer.fromBinary.fromBinary(payload)
      val response: Array[Byte] =  serializer.toBinary.toBinary(requestHandler(request))

      log.debug("%s sending reply to %s", this, props.getReplyTo)
      val replyProps = new BasicProperties
      replyProps.setCorrelationId(props.getCorrelationId)
      producer ! new Message(response, props.getReplyTo, properties = Some(replyProps))

      sender.foreach(_ ! Acknowledge(tag))
    }
    case Acknowledged(tag) => log.debug("%s acknowledged delivery with tag %d", this, tag)
  }

  override def toString(): String =
    "AMQP.RpcServer[]"
}