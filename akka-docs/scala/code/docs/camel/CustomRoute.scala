/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.camel

import akka.camel.CamelMessage
import akka.actor.Status.Failure

import language.existentials

object CustomRoute {
  object Sample1 {
    //#CustomRoute
    import akka.actor.{ Props, ActorSystem, Actor, ActorRef }
    import akka.camel.{ CamelMessage, CamelExtension }
    import org.apache.camel.builder.RouteBuilder
    import akka.camel._
    class Responder extends Actor {
      def receive = {
        case msg: CamelMessage ⇒
          sender ! (msg.mapBody {
            body: String ⇒ "received %s" format body
          })
      }
    }

    class CustomRouteBuilder(system: ActorSystem, responder: ActorRef) extends RouteBuilder {
      def configure {
        from("jetty:http://localhost:8877/camel/custom").to(responder)
      }
    }
    val system = ActorSystem("some-system")
    val camel = CamelExtension(system)
    val responder = system.actorOf(Props[Responder], name = "TestResponder")
    camel.context.addRoutes(new CustomRouteBuilder(system, responder))
    //#CustomRoute

  }
  object Sample2 {
    //#ErrorThrowingConsumer
    import akka.camel.Consumer

    import org.apache.camel.builder.Builder
    import org.apache.camel.model.RouteDefinition

    class ErrorThrowingConsumer(override val endpointUri: String) extends Consumer {
      def receive = {
        case msg: CamelMessage ⇒ throw new Exception("error: %s" format msg.body)
      }
      override def onRouteDefinition(rd: RouteDefinition) = {
        // Catch any exception and handle it by returning the exception message as response
        rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
      }

      final override def preRestart(reason: Throwable, message: Option[Any]) {
        sender ! Failure(reason)
      }
    }
    //#ErrorThrowingConsumer
  }

}
