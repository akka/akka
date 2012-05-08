package akka.docs.camel

object Introduction {
  {
    //#Consumer-mina
    import akka.camel.{ CamelMessage, Consumer }

    class MinaClient extends Consumer {
      def endpointUri = "mina:tcp://localhost:6200?textline=true"

      def receive = {
        case msg: CamelMessage ⇒ { /* ... */ }
        case _                 ⇒ { /* ... */ }
      }
    }

    // start and expose actor via tcp
    import akka.actor.{ ActorSystem, Props }

    val sys = ActorSystem("camel")
    val mina = sys.actorOf(Props[MinaClient])
    //#Consumer-mina
  }
  {
    //#Consumer
    import akka.camel.{ CamelMessage, Consumer }

    class JettyAdapter extends Consumer {
      def endpointUri = "jetty:http://localhost:8877/example"

      def receive = {
        case msg: CamelMessage ⇒ { /* ... */ }
        case _                 ⇒ { /* ... */ }
      }
    }
    //#Consumer
  }
  {
    //#Producer
    import akka.actor.Actor
    import akka.camel.{ Producer, Oneway }
    import akka.actor.{ ActorSystem, Props }

    class Orders extends Actor with Producer with Oneway {
      def endpointUri = "jms:queue:Orders"
    }

    val sys = ActorSystem("camel")
    val orders = sys.actorOf(Props[Orders])

    orders ! <order amount="100" currency="PLN" itemId="12345"/>
    //#Producer
  }
}