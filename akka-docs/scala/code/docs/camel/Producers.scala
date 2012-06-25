package docs.camel

object Producers {
  {
    //#Producer1
    import akka.camel.Producer
    import akka.actor.Actor

    class Producer1 extends Actor with Producer {
      def endpointUri = "http://localhost:8080/news"
    }
    //#Producer1
  }
}
