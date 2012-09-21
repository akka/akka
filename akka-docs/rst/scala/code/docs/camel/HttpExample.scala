package docs.camel

object HttpExample {

  {
    //#HttpExample
    import org.apache.camel.Exchange
    import akka.actor.{ Actor, ActorRef, Props, ActorSystem }
    import akka.camel.{ Producer, CamelMessage, Consumer }
    import akka.actor.Status.Failure

    class HttpConsumer(producer: ActorRef) extends Consumer {
      def endpointUri = "jetty:http://0.0.0.0:8875/"

      def receive = {
        case msg ⇒ producer forward msg
      }
    }

    class HttpProducer(transformer: ActorRef) extends Actor with Producer {
      def endpointUri = "jetty://http://akka.io/?bridgeEndpoint=true"

      override def transformOutgoingMessage(msg: Any) = msg match {
        case msg: CamelMessage ⇒ msg.addHeaders(msg.headers(Set(Exchange.HTTP_PATH)))
      }

      override def routeResponse(msg: Any) { transformer forward msg }
    }

    class HttpTransformer extends Actor {
      def receive = {
        case msg: CamelMessage ⇒ sender ! (msg.mapBody { body: Array[Byte] ⇒ new String(body).replaceAll("Akka ", "AKKA ") })
        case msg: Failure      ⇒ sender ! msg
      }
    }

    // Create the actors. this can be done in a Boot class so you can
    // run the example in the MicroKernel. just add the below three lines to your boot class.
    val system = ActorSystem("some-system")
    val httpTransformer = system.actorOf(Props[HttpTransformer])
    val httpProducer = system.actorOf(Props(new HttpProducer(httpTransformer)))
    val httpConsumer = system.actorOf(Props(new HttpConsumer(httpProducer)))
    //#HttpExample

  }

}
