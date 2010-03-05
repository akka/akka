package sample.camel

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.camel.Message

/**
 * @author Martin Krasser
 */
class Transformer(producer: Actor) extends Actor {

  protected def receive = {
    case msg: Message => producer.forward(msg.transformBody[String]("- %s -" format _))
  }

}