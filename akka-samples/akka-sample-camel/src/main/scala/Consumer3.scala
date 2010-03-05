package sample.camel

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.camel.{Message, Consumer}

/**
 * @author Martin Krasser
 */
class Consumer3(transformer: Actor) extends Actor with Consumer {

  def endpointUri = "jetty:http://0.0.0.0:8877/camel/welcome"

  def receive = {
    case msg: Message => transformer.forward(msg.setBodyAs(classOf[String]))
  }

}
