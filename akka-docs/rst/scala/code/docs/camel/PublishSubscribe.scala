package docs.camel

object PublishSubscribe {
  {
    //#PubSub
    import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
    import akka.camel.{ Producer, CamelMessage, Consumer }

    class Subscriber(name: String, uri: String) extends Actor with Consumer {
      def endpointUri = uri

      def receive = {
        case msg: CamelMessage ⇒ println("%s received: %s" format (name, msg.body))
      }
    }

    class Publisher(name: String, uri: String) extends Actor with Producer {

      def endpointUri = uri

      // one-way communication with JMS
      override def oneway = true
    }

    class PublisherBridge(uri: String, publisher: ActorRef) extends Actor with Consumer {
      def endpointUri = uri

      def receive = {
        case msg: CamelMessage ⇒ {
          publisher ! msg.bodyAs[String]
          sender ! ("message published")
        }
      }
    }

    // Add below to a Boot class
    // Setup publish/subscribe example
    val system = ActorSystem("some-system")
    val jmsUri = "jms:topic:test"
    val jmsSubscriber1 = system.actorOf(Props(new Subscriber("jms-subscriber-1", jmsUri)))
    val jmsSubscriber2 = system.actorOf(Props(new Subscriber("jms-subscriber-2", jmsUri)))
    val jmsPublisher = system.actorOf(Props(new Publisher("jms-publisher", jmsUri)))
    val jmsPublisherBridge = system.actorOf(Props(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/jms", jmsPublisher)))
    //#PubSub

  }
}
