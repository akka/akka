/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{ActorRef, Actor}
import com.rabbitmq.client.AMQP.BasicProperties
import se.scalablesolutions.akka.config.ScalaConfig.{LifeCycle, Permanent}

class RpcClientActor(producer: ActorRef, routingKey: String, replyTo: String) extends Actor {

  self.lifeCycle = Some(LifeCycle(Permanent))
  
  log.info("%s started", this)

  protected def receive = {
    case payload: Array[Byte] => {
      val props = new BasicProperties
      props.setReplyTo(replyTo)
      producer ! new Message(payload, routingKey, properties = Some(props))
    }
  }

  override def toString(): String =
    "AMQP.RpcClient[producerId=" + producer.id +
            ", routingKey=" + routingKey+
            ", replyTo=" + replyTo + "]"

}