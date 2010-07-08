/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{ActorRef, Actor}
import com.rabbitmq.client.AMQP.BasicProperties

class RpcClientActor(producer: ActorRef, routingKey: String, replyTo: String) extends Actor {
  
  protected def receive = {
    case payload: Array[Byte] => {
      val props = new BasicProperties
      props.setReplyTo(replyTo)
      producer ! new Message(payload, routingKey, properties = Some(props))
    }
  }
}