package sample.camel

import se.scalablesolutions.akka.actor.{Actor, RemoteActor}
import se.scalablesolutions.akka.annotation.consume
import se.scalablesolutions.akka.camel.{Message, Consumer}
import se.scalablesolutions.akka.util.Logging

/**
 * Client-initiated remote actor.
 */
class RemoteActor1 extends RemoteActor("localhost", 7777) with Consumer {
  def endpointUri = "jetty:http://localhost:6644/remote1"

  protected def receive = {
    case msg => reply("response from remote actor 1")
  }
}

/**
 * Server-initiated remote actor.
 */
class RemoteActor2 extends Actor with Consumer {
  def endpointUri = "jetty:http://localhost:6644/remote2"

  protected def receive = {
    case msg => reply("response from remote actor 2")
  }
}

class Consumer1 extends Actor with Consumer with Logging {
  def endpointUri = "file:data/input"

  def receive = {
    case msg: Message => log.info("received %s" format msg.bodyAs(classOf[String]))
  }
}

@consume("jetty:http://0.0.0.0:8877/camel/test1")
class Consumer2 extends Actor {
  def receive = {
    case msg: Message => reply("Hello %s" format msg.bodyAs(classOf[String]))
  }
}

class Consumer3(transformer: Actor) extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/welcome"

  def receive = {
    case msg: Message => transformer.forward(msg.setBodyAs(classOf[String]))
  }
}

class Transformer(producer: Actor) extends Actor {
  protected def receive = {
    case msg: Message => producer.forward(msg.transformBody[String]("- %s -" format _))
  }
}