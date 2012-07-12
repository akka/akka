package docs.camel


object CustomRouteExample {
  {
    //#CustomRouteExample
    import akka.actor.{Actor, ActorRef, Props, ActorSystem}
    import akka.camel.{CamelMessage, Consumer, Producer, CamelExtension}
    import org.apache.camel.builder.RouteBuilder
    import org.apache.camel.{Exchange, Processor}

    class Consumer3(transformer: ActorRef) extends Actor with Consumer {
      def endpointUri = "jetty:http://0.0.0.0:8877/camel/welcome"

      def receive = {
        // Forward a string representation of the message body to transformer
        case msg: CamelMessage => transformer.forward(msg.bodyAs[String])
      }
    }

    class Transformer(producer: ActorRef) extends Actor {
      def receive = {
        // example: transform message body "foo" to "- foo -" and forward result to producer
        case msg: CamelMessage => producer.forward(msg.mapBody((body: String) => "- %s -" format body))
      }
    }

    class Producer1 extends Actor with Producer {
      def endpointUri = "direct:welcome"
    }

    class CustomRouteBuilder extends RouteBuilder {
      def configure {
        from("direct:welcome").process(new Processor() {
          def process(exchange: Exchange) {
            // Create a 'welcome' message from the input message
            exchange.getOut.setBody("Welcome %s" format exchange.getIn.getBody)
          }
        })
      }
    }
    // the below lines can be added to a Boot class, so that you can run the example from a MicroKernel
    val system = ActorSystem("some-system")
    val producer = system.actorOf(Props[Producer1])
    val mediator = system.actorOf(Props(new Transformer(producer)))
    val consumer = system.actorOf(Props(new Consumer3(mediator)))
    CamelExtension(system).context.addRoutes(new CustomRouteBuilder)
    //#CustomRouteExample
  }

}
