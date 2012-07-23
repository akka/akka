package docs.camel

object Consumers {
  {
    //#Consumer1
    import akka.camel.{ CamelMessage, Consumer }

    class Consumer1 extends Consumer {
      def endpointUri = "file:data/input/actor"

      def receive = {
        case msg: CamelMessage ⇒ println("received %s" format msg.bodyAs[String])
      }
    }
    //#Consumer1
  }
  {
    //#Consumer2
    import akka.camel.{ CamelMessage, Consumer }

    class Consumer2 extends Consumer {
      def endpointUri = "jetty:http://localhost:8877/camel/default"

      def receive = {
        case msg: CamelMessage ⇒ sender ! ("Hello %s" format msg.bodyAs[String])
      }
    }
    //#Consumer2
  }
  {
    //#Consumer3
    import akka.camel.{ CamelMessage, Consumer }
    import akka.camel.Ack
    import akka.actor.Status.Failure

    class Consumer3 extends Consumer {
      override def autoAck = false

      def endpointUri = "jms:queue:test"

      def receive = {
        case msg: CamelMessage ⇒
          sender ! Ack
          // on success
          // ..
          val someException = new Exception("e1")
          // on failure
          sender ! Failure(someException)
      }
    }
    //#Consumer3
  }
  {
    //#Consumer4
    import akka.camel.{ CamelMessage, Consumer }
    import akka.util.duration._

    class Consumer4 extends Consumer {
      def endpointUri = "jetty:http://localhost:8877/camel/default"
      override def replyTimeout = 500 millis
      def receive = {
        case msg: CamelMessage ⇒ sender ! ("Hello %s" format msg.bodyAs[String])
      }
    }
    //#Consumer4
  }
}