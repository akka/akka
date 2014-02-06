package sample.camel

import org.apache.camel.Exchange
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Status.Failure
import akka.actor.actorRef2Scala
import akka.camel.CamelMessage
import akka.camel.Consumer
import akka.camel.Producer

object HttpExample {

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("some-system")
    val httpTransformer = system.actorOf(Props[HttpTransformer])
    val httpProducer = system.actorOf(Props(classOf[HttpProducer], httpTransformer))
    val httpConsumer = system.actorOf(Props(classOf[HttpConsumer], httpProducer))
  }

  class HttpConsumer(producer: ActorRef) extends Consumer {
    def endpointUri = "jetty:http://0.0.0.0:8875/"

    def receive = {
      case msg => producer forward msg
    }
  }

  class HttpProducer(transformer: ActorRef) extends Actor with Producer {
    // bridgeEndpoint=true makes the producer ignore the Exchange.HTTP_URI header, 
    // and use the endpoint's URI for request
    def endpointUri = "jetty://http://akka.io/?bridgeEndpoint=true"

    // before producing messages to endpoints, producer actors can pre-process
    // them by overriding the transformOutgoingMessage method  
    override def transformOutgoingMessage(msg: Any) = msg match {
      case camelMsg: CamelMessage => camelMsg.copy(headers =
        camelMsg.headers(Set(Exchange.HTTP_PATH)))
    }

    // instead of replying to the initial sender(), producer actors can implement custom
    // response processing by overriding the routeResponse method
    override def routeResponse(msg: Any) { transformer forward msg }
  }

  class HttpTransformer extends Actor {
    def receive = {
      case msg: CamelMessage =>
        sender() ! (msg.mapBody { body: Array[Byte] =>
          new String(body).replaceAll("Akka ", "AKKA ")
        })
      case msg: Failure => sender() ! msg
    }
  }

}
