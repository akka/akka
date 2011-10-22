package sample.camel

import org.apache.camel.Exchange

import akka.actor.{ Actor, ActorRef, ActorRegistry }
import akka.camel.{ Ack, Failure, Producer, Message, Consumer }

/**
 * Client-initiated remote actor.
 */
class RemoteActor1 extends Actor with Consumer {
  def endpointUri = "jetty:http://localhost:6644/camel/remote-actor-1"

  protected def receive = {
    case msg: Message ⇒ sender ! Message("hello %s" format msg.bodyAs[String], Map("sender" -> "remote1"))
  }
}

/**
 * Server-initiated remote actor.
 */
class RemoteActor2 extends Actor with Consumer {
  def endpointUri = "jetty:http://localhost:6644/camel/remote-actor-2"

  protected def receive = {
    case msg: Message ⇒ sender ! Message("hello %s" format msg.bodyAs[String], Map("sender" -> "remote2"))
  }
}

class Producer1 extends Actor with Producer {
  def endpointUri = "direct:welcome"
  override def oneway = false // default
}

class Consumer1 extends Actor with Consumer {
  def endpointUri = "file:data/input/actor"

  def receive = {
    case msg: Message ⇒ println("received %s" format msg.bodyAs[String])
  }
}

class Consumer2 extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/default"

  def receive = {
    case msg: Message ⇒ sender ! ("Hello %s" format msg.bodyAs[String])
  }
}

class Consumer3(transformer: ActorRef) extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/welcome"

  def receive = {
    case msg: Message ⇒ transformer.forward(msg.setBodyAs[String])
  }
}

class Consumer4 extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/stop"

  def receive = {
    case msg: Message ⇒ msg.bodyAs[String] match {
      case "stop" ⇒ {
        sender ! "Consumer4 stopped"
        self.stop
      }
      case body ⇒ sender ! body
    }
  }
}

class Consumer5 extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8877/camel/start"

  def receive = {
    case _ ⇒ {
      Actor.actorOf[Consumer4]
      sender ! "Consumer4 started"
    }
  }
}

class Transformer(producer: ActorRef) extends Actor {
  protected def receive = {
    case msg: Message ⇒ producer.forward(msg.transformBody((body: String) ⇒ "- %s -" format body))
  }
}

class Subscriber(name: String, uri: String) extends Actor with Consumer {
  def endpointUri = uri

  protected def receive = {
    case msg: Message ⇒ println("%s received: %s" format (name, msg.body))
  }
}

class Publisher(uri: String) extends Actor with Producer {
  def endpointUri = uri
  override def oneway = true
}

class PublisherBridge(uri: String, publisher: ActorRef) extends Actor with Consumer {
  def endpointUri = uri

  protected def receive = {
    case msg: Message ⇒ {
      publisher ! msg.bodyAs[String]
      sender ! "message published"
    }
  }
}

class HttpConsumer(producer: ActorRef) extends Actor with Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8875/"

  protected def receive = {
    case msg ⇒ producer forward msg
  }
}

class HttpProducer(transformer: ActorRef) extends Actor with Producer {
  def endpointUri = "jetty://http://akka.io/?bridgeEndpoint=true"

  override protected def receiveBeforeProduce = {
    // only keep Exchange.HTTP_PATH message header (which needed by bridge endpoint)
    case msg: Message ⇒ msg.setHeaders(msg.headers(Set(Exchange.HTTP_PATH)))
  }

  override protected def receiveAfterProduce = {
    // do not reply but forward result to transformer
    case msg ⇒ transformer forward msg
  }
}

class HttpTransformer extends Actor {
  protected def receive = {
    case msg: Message ⇒ sender ! (msg.transformBody { body: String ⇒ body replaceAll ("Akka ", "AKKA ") })
    case msg: Failure ⇒ sender ! msg
  }
}

class FileConsumer extends Actor with Consumer {
  def endpointUri = "file:data/input/actor?delete=true"
  override def autoack = false

  var counter = 0

  def receive = {
    case msg: Message ⇒ {
      if (counter == 2) {
        println("received %s" format msg.bodyAs[String])
        sender ! Ack
      } else {
        println("rejected %s" format msg.bodyAs[String])
        counter += 1
        sender ! Failure(new Exception("message number %s not accepted" format counter))
      }
    }
  }
}
