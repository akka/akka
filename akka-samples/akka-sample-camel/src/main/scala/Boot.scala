package sample.camel

import org.apache.camel.{Exchange, Processor}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.spring.spi.ApplicationContextRegistry
import org.springframework.context.support.ClassPathXmlApplicationContext

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.camel.CamelContextManager
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.actor.{ActiveObject, SupervisorFactory}

/**
 * @author Martin Krasser
 */
class Boot {

  // Create CamelContext with Spring-based registry and custom route builder

  val context = new ClassPathXmlApplicationContext("/sample-camel-context.xml", getClass)
  val registry = new ApplicationContextRegistry(context)
  CamelContextManager.init(new DefaultCamelContext(registry))
  CamelContextManager.context.addRoutes(new CustomRouteBuilder)

  // Basic example

  val factory = SupervisorFactory(
    SupervisorConfig(
      RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
      Supervise(actorOf[Consumer1], LifeCycle(Permanent)) ::
      Supervise(actorOf[Consumer2], LifeCycle(Permanent)) :: Nil))
  factory.newInstance.start

  // Routing example

  val producer = actorOf[Producer1]
  val mediator = actorOf(new Transformer(producer))
  val consumer = actorOf(new Consumer3(mediator))

  producer.start
  mediator.start
  consumer.start

  // Publish subscribe example

  //
  // Cometd example commented out because camel-cometd is broken in Camel 2.3
  //

  //val cometdUri = "cometd://localhost:8111/test/abc?baseResource=file:target"
  //val cometdSubscriber = actorOf(new Subscriber("cometd-subscriber", cometdUri)).start
  //val cometdPublisher = actorOf(new Publisher("cometd-publisher", cometdUri)).start

  val jmsUri = "jms:topic:test"
  val jmsSubscriber1 = actorOf(new Subscriber("jms-subscriber-1", jmsUri)).start
  val jmsSubscriber2 = actorOf(new Subscriber("jms-subscriber-2", jmsUri)).start
  val jmsPublisher =   actorOf(new Publisher("jms-publisher", jmsUri)).start

  //val cometdPublisherBridge = actorOf(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/cometd", cometdPublisher)).start
  val jmsPublisherBridge = actorOf(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/jms", jmsPublisher)).start

  actorOf[Consumer4].start // POSTing "stop" to http://0.0.0.0:8877/camel/stop stops and unpublishes this actor
  actorOf[Consumer5].start // POSTing any msg to http://0.0.0.0:8877/camel/start starts and published Consumer4 again.

  // Publish active object methods on endpoints
  ActiveObject.newInstance(classOf[Consumer10])
}

class CustomRouteBuilder extends RouteBuilder {
  def configure {
    val actorUri = "actor:%s" format classOf[Consumer2].getName
    from("jetty:http://0.0.0.0:8877/camel/test2").to(actorUri)
    from("direct:welcome").process(new Processor() {
      def process(exchange: Exchange) {
        exchange.getOut.setBody("Welcome %s" format exchange.getIn.getBody)
      }
    })
  }
}
