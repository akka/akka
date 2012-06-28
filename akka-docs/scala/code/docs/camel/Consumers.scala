package docs.camel

object Consumers {
  def foo = {
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
  def bar = {
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
}