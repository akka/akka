/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{ActorRef, Actor}
import se.scalablesolutions.akka.config.ScalaConfig.{Permanent, LifeCycle}

class RpcServerActor(producer: ActorRef, requestHandler: Function[Array[Byte], Array[Byte]]) extends Actor {

  self.lifeCycle = Some(LifeCycle(Permanent))

  log.info("%s started", this)

  protected def receive = {
    case Delivery(payload, _, tag, props, sender) => {

      val response: Array[Byte] =  requestHandler(payload)

      log.info("Sending reply to %s", props.getReplyTo)
      producer ! new Message(response, props.getReplyTo)

      sender.foreach(_ ! Acknowledge(tag))
    }
    case Acknowledged(tag) => log.debug("todo")
  }
}