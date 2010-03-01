package sample.camel

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.camel.{Message, Consumer}

/**
 * @author Martin Krasser
 */
class Consumer1 extends Actor with Consumer with Logging {

  def endpointUri = "file:data/input"

  def receive = {
    case msg: Message => log.info("received %s" format msg.bodyAs(classOf[String]))
  }

}