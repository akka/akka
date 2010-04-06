package sample.camel

import se.scalablesolutions.akka.actor.{Actor, RemoteActor}
import se.scalablesolutions.akka.actor.annotation.consume
import se.scalablesolutions.akka.camel.{Producer, Message, Consumer}
import se.scalablesolutions.akka.util.Logging

/**
 * Client-initiated remote actor.
 */
class RemoteActor1 extends RemoteActor("localhost", 7777) with Consumer {
  def endpointUri = "jetty:http://localhost:6644/remote1"

  protected def receive = {
    case msg: Message => reply(Message("hello %s" format msg.body, Map("sender" -> "remote1")))
  }
}

/**
 * Server-initiated remote actor.
 */
class RemoteActor2 extends Actor with Consumer {
  def endpointUri = "jetty:http://localhost:6644/remote2"

  protected def receive = {
    case msg: Message => reply(Message("hello %s" format msg.body, Map("sender" -> "remote2")))
  }
}

class Producer1 extends Actor with Producer {
  def endpointUri = "direct:welcome"

  override def oneway = false // default
  override def async = true   // default

  protected def receive = produce
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

class Subscriber(name:String, uri: String) extends Actor with Consumer with Logging {
  def endpointUri = uri

  protected def receive = {
    case msg: Message => log.info("%s received: %s" format (name, msg.body))
  }
}

class Publisher(name: String, uri: String) extends Actor with Producer {
  id = name
  def endpointUri = uri
  override def oneway = true
  protected def receive = produce
}

class PublisherBridge(uri: String, publisher: Actor) extends Actor with Consumer {
  def endpointUri = uri

  protected def receive = {
    case msg: Message => {
      publisher ! msg.bodyAs(classOf[String])
      reply("message published")
    }
  }
}
