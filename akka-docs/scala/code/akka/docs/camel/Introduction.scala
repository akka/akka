package akka.docs.camel

object wrapper {
  {
    //#Consumer-mina
    import akka.camel.{ CamelMessage, Consumer }

    class MyActor extends Consumer {
      def endpointUri = "mina:tcp://localhost:6200?textline=true"

      def receive = {
        case msg: CamelMessage ⇒ { /* ... */ }
        case _                 ⇒ { /* ... */ }
      }
    }

    // start and expose actor via tcp
    import akka.actor.{ ActorSystem, Props }

    val sys = ActorSystem("camel")
    val myActor = sys.actorOf(Props[MyActor])
    //#Consumer-mina
  }
  {
    //#Consumer
    import akka.camel.{ CamelMessage, Consumer }

    class MyActor extends Consumer {
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

    class MyActor extends Actor with Producer with Oneway {
      def endpointUri = "jms:queue:example"
    }
    //#Producer
  }
}