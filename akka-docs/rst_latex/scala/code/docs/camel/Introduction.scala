package docs.camel

import akka.actor.{ Props, ActorSystem }
import akka.camel.CamelExtension

import language.postfixOps
import akka.util.Timeout

object Introduction {
  def foo = {
    //#Consumer-mina
    import akka.camel.{ CamelMessage, Consumer }

    class MyEndpoint extends Consumer {
      def endpointUri = "mina:tcp://localhost:6200?textline=true"

      def receive = {
        case msg: CamelMessage ⇒ { /* ... */ }
        case _                 ⇒ { /* ... */ }
      }
    }

    // start and expose actor via tcp
    import akka.actor.{ ActorSystem, Props }

    val system = ActorSystem("some-system")
    val mina = system.actorOf(Props[MyEndpoint])
    //#Consumer-mina
  }
  def bar = {
    //#Consumer
    import akka.camel.{ CamelMessage, Consumer }

    class MyEndpoint extends Consumer {
      def endpointUri = "jetty:http://localhost:8877/example"

      def receive = {
        case msg: CamelMessage ⇒ { /* ... */ }
        case _                 ⇒ { /* ... */ }
      }
    }
    //#Consumer
  }
  def baz = {
    //#Producer
    import akka.actor.Actor
    import akka.camel.{ Producer, Oneway }
    import akka.actor.{ ActorSystem, Props }

    class Orders extends Actor with Producer with Oneway {
      def endpointUri = "jms:queue:Orders"
    }

    val sys = ActorSystem("some-system")
    val orders = sys.actorOf(Props[Orders])

    orders ! <order amount="100" currency="PLN" itemId="12345"/>
    //#Producer
  }
  {
    //#CamelExtension
    val system = ActorSystem("some-system")
    val camel = CamelExtension(system)
    val camelContext = camel.context
    val producerTemplate = camel.template

    //#CamelExtension
  }
  {
    //#CamelExtensionAddComponent
    // import org.apache.activemq.camel.component.ActiveMQComponent
    val system = ActorSystem("some-system")
    val camel = CamelExtension(system)
    val camelContext = camel.context
    // camelContext.addComponent("activemq", ActiveMQComponent.activeMQComponent("vm://localhost?broker.persistent=false"))
    //#CamelExtensionAddComponent
  }
  {
    //#CamelActivation
    import akka.camel.{ CamelMessage, Consumer }
    import scala.concurrent.util.duration._

    class MyEndpoint extends Consumer {
      def endpointUri = "mina:tcp://localhost:6200?textline=true"

      def receive = {
        case msg: CamelMessage ⇒ { /* ... */ }
        case _                 ⇒ { /* ... */ }
      }
    }
    val system = ActorSystem("some-system")
    val camel = CamelExtension(system)
    val actorRef = system.actorOf(Props[MyEndpoint])
    // get a future reference to the activation of the endpoint of the Consumer Actor
    val activationFuture = camel.activationFutureFor(actorRef)(timeout = 10 seconds, executor = system.dispatcher)
    //#CamelActivation
    //#CamelDeactivation
    system.stop(actorRef)
    // get a future reference to the deactivation of the endpoint of the Consumer Actor
    val deactivationFuture = camel.deactivationFutureFor(actorRef)(timeout = 10 seconds, executor = system.dispatcher)
    //#CamelDeactivation
  }

}