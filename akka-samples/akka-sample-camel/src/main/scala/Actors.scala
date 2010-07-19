package sample.camel

import org.apache.camel.Exchange

import se.scalablesolutions.akka.actor.{Actor, ActorRef, RemoteActor}
import se.scalablesolutions.akka.camel.{Failure, Producer, Message, Consumer}
import se.scalablesolutions.akka.util.Logging

/**
 * Client-initiated remote actor.
 */
class RemoteActor1 extends RemoteActor("localhost", 7777) with Consumer {
  def endpointUri = "jetty:http://localhost:6644/camel/remote-actor-1"

  protected def receive = {
    case msg: Message => self.reply(Message("hello %s" format msg.bodyAs[String], Map("sender" -> "remote1")))
  }
}

/**
 * Server-initiated remote actor.
 */
class RemoteActor2 extends Actor with Consumer {
  def endpointUri = "jetty:http://localhost:6644/camel/remote-actor-2"

  protected def receive = {
    case msg: Message => self.reply(Message("hello %s" format msg.bodyAs[String], Map("sender" -> "remote2")))
  }
}

class Producer1 extends Actor with Producer {
  def endpointUri = "direct:welcome"
  override def oneway = false // default
}

class Consumer1 extends Actor with Consumer with Logging {
  def endpointUri = "file:data/input/actor"

  def receive = {
    case msg: Message => log.info("received %s" format msg.bodyAs[String])
  }
}

class Consumer2 extends Actor {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/default"

  def receive = {
    case msg: Message => self.reply("Hello %s" format msg.bodyAs[String])
  }
}

class Consumer3(transformer: ActorRef) extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/welcome"

  def receive = {
    case msg: Message => transformer.forward(msg.setBodyAs[String])
  }
}

class Consumer4 extends Actor with Consumer with Logging {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/stop"

  def receive = {
    case msg: Message => msg.bodyAs[String] match {
      case "stop" => {
        self.reply("Consumer4 stopped")
        self.stop
      }
      case body => self.reply(body)
    }
  }
}

class Consumer5 extends Actor with Consumer with Logging {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/start"

  def receive = {
    case _ => {
      Actor.actorOf[Consumer4].start
      self.reply("Consumer4 started")
    }
  }
}

class Transformer(producer: ActorRef) extends Actor {
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
  self.id = name
  def endpointUri = uri
  override def oneway = true
}

class PublisherBridge(uri: String, publisher: ActorRef) extends Actor with Consumer {
  def endpointUri = uri

  protected def receive = {
    case msg: Message => {
      publisher ! msg.bodyAs[String]
      self.reply("message published")
    }
  }
}

class HttpConsumer(producer: ActorRef) extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8875/"
  
  protected def receive = {
    // only keep Exchange.HTTP_PATH message header (which needed by bridge endpoint) 
    case msg: Message => producer forward msg.setHeaders(msg.headers(Set(Exchange.HTTP_PATH)))
  }
}

class HttpProducer(transformer: ActorRef) extends Actor with Producer {
  def endpointUri = "jetty://http://akkasource.org/?bridgeEndpoint=true"

  override protected def receiveAfterProduce = {
    // do not reply but forward result to transformer
    case msg => transformer forward msg
  }
}

class HttpTransformer extends Actor {
  protected def receive = {
    case msg: Message => self.reply(msg.transformBody[String] {_ replaceAll ("Akka ", "AKKA ")})
    case msg: Failure => self.reply(msg)
  }
}
