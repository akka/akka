package docs.camel

import akka.camel.CamelExtension
import language.postfixOps

object Producers {
  object Sample1 {
    //#Producer1
    import akka.actor.Actor
    import akka.actor.{ Props, ActorSystem }
    import akka.camel.{ Producer, CamelMessage }
    import akka.util.Timeout

    class Producer1 extends Actor with Producer {
      def endpointUri = "http://localhost:8080/news"
    }
    //#Producer1
    //#AskProducer
    import akka.pattern.ask
    import scala.concurrent.util.duration._
    implicit val timeout = Timeout(10 seconds)

    val system = ActorSystem("some-system")
    val producer = system.actorOf(Props[Producer1])
    val future = producer.ask("some request").mapTo[CamelMessage]
    //#AskProducer
  }
  object Sample2 {
    //#RouteResponse
    import akka.actor.{ Actor, ActorRef }
    import akka.camel.{ Producer, CamelMessage }
    import akka.actor.{ Props, ActorSystem }

    class ResponseReceiver extends Actor {
      def receive = {
        case msg: CamelMessage ⇒
        // do something with the forwarded response
      }
    }

    class Forwarder(uri: String, target: ActorRef) extends Actor with Producer {
      def endpointUri = uri

      override def routeResponse(msg: Any) { target forward msg }
    }
    val system = ActorSystem("some-system")
    val receiver = system.actorOf(Props[ResponseReceiver])
    val forwardResponse = system.actorOf(Props(
      new Forwarder("http://localhost:8080/news/akka", receiver)))
    // the Forwarder sends out a request to the web page and forwards the response to
    // the ResponseReceiver
    forwardResponse ! "some request"
    //#RouteResponse
  }
  object Sample3 {
    //#TransformOutgoingMessage
    import akka.actor.Actor
    import akka.camel.{ Producer, CamelMessage }

    class Transformer(uri: String) extends Actor with Producer {
      def endpointUri = uri

      def upperCase(msg: CamelMessage) = msg.mapBody {
        body: String ⇒ body.toUpperCase
      }

      override def transformOutgoingMessage(msg: Any) = msg match {
        case msg: CamelMessage ⇒ upperCase(msg)
      }
    }
    //#TransformOutgoingMessage
  }
  object Sample4 {
    //#Oneway
    import akka.actor.{ Actor, Props, ActorSystem }
    import akka.camel.Producer

    class OnewaySender(uri: String) extends Actor with Producer {
      def endpointUri = uri
      override def oneway: Boolean = true
    }

    val system = ActorSystem("some-system")
    val producer = system.actorOf(Props(new OnewaySender("activemq:FOO.BAR")))
    producer ! "Some message"
    //#Oneway

  }
  object Sample5 {
    //#Correlate
    import akka.camel.{ Producer, CamelMessage }
    import akka.actor.Actor
    import akka.actor.{ Props, ActorSystem }

    class Producer2 extends Actor with Producer {
      def endpointUri = "activemq:FOO.BAR"
    }
    val system = ActorSystem("some-system")
    val producer = system.actorOf(Props[Producer2])

    producer ! CamelMessage("bar", Map(CamelMessage.MessageExchangeId -> "123"))
    //#Correlate
  }
  object Sample6 {
    //#ProducerTemplate
    import akka.actor.Actor
    class MyActor extends Actor {
      def receive = {
        case msg ⇒
          val template = CamelExtension(context.system).template
          template.sendBody("direct:news", msg)
      }
    }
    //#ProducerTemplate
  }
  object Sample7 {
    //#RequestProducerTemplate
    import akka.actor.Actor
    class MyActor extends Actor {
      def receive = {
        case msg ⇒
          val template = CamelExtension(context.system).template
          sender ! template.requestBody("direct:news", msg)
      }
    }
    //#RequestProducerTemplate
  }

}