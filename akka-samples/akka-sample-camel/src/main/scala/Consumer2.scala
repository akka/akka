package sample.camel

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.annotation.consume
import se.scalablesolutions.akka.camel.Message

/**
 * @author Martin Krasser
 */
@consume("jetty:http://0.0.0.0:8877/camel/test1")
class Consumer2 extends Actor {

  def receive = {
    case msg: Message => reply("Hello %s" format msg.bodyAs(classOf[String]))
  }

}