package sample.camel

import org.apache.camel.{ Exchange, Processor }
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.spring.spi.ApplicationContextRegistry
import org.springframework.context.support.ClassPathXmlApplicationContext

import akka.actor.Actor._
import akka.actor.TypedActor
import akka.actor.TypedActor.Configuration._
import akka.camel.CamelContextManager
import akka.config.Supervision._

/**
 * @author Martin Krasser
 */
class Boot {

  // -----------------------------------------------------------------------
  // Basic example
  // -----------------------------------------------------------------------

  actorOf[Consumer1].start
  actorOf[Consumer2].start

  // Alternatively, use a supervisor for these actors
  //val supervisor = Supervisor(
  //  SupervisorConfig(
  //    RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
  //    Supervise(actorOf[Consumer1], Permanent) ::
  //    Supervise(actorOf[Consumer2], Permanent) :: Nil))

  // -----------------------------------------------------------------------
  // Custom Camel route example
  // -----------------------------------------------------------------------

  // Create CamelContext and a Spring-based registry
  val context = new ClassPathXmlApplicationContext("/context-jms.xml", getClass)
  val registry = new ApplicationContextRegistry(context)

  // Use a custom Camel context and a custom touter builder
  CamelContextManager.init(new DefaultCamelContext(registry))
  CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder)

  val producer = actorOf[Producer1]
  val mediator = actorOf(new Transformer(producer))
  val consumer = actorOf(new Consumer3(mediator))

  producer.start
  mediator.start
  consumer.start

  // -----------------------------------------------------------------------
  // Asynchronous consumer-producer example (Akka homepage transformation)
  // -----------------------------------------------------------------------

  val httpTransformer = actorOf(new HttpTransformer).start
  val httpProducer = actorOf(new HttpProducer(httpTransformer)).start
  val httpConsumer = actorOf(new HttpConsumer(httpProducer)).start

  // -----------------------------------------------------------------------
  // Publish subscribe examples
  // -----------------------------------------------------------------------

  //
  // Cometd example commented out because camel-cometd is broken since Camel 2.3
  //

  //val cometdUri = "cometd://localhost:8111/test/abc?baseResource=file:target"
  //val cometdSubscriber = actorOf(new Subscriber("cometd-subscriber", cometdUri)).start
  //val cometdPublisher = actorOf(new Publisher("cometd-publisher", cometdUri)).start

  val jmsUri = "jms:topic:test"
  val jmsSubscriber1 = actorOf(new Subscriber("jms-subscriber-1", jmsUri)).start
  val jmsSubscriber2 = actorOf(new Subscriber("jms-subscriber-2", jmsUri)).start
  val jmsPublisher = actorOf(new Publisher(jmsUri), "jms-publisher").start

  //val cometdPublisherBridge = actorOf(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/cometd", cometdPublisher)).start
  val jmsPublisherBridge = actorOf(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/jms", jmsPublisher)).start

  // -----------------------------------------------------------------------
  // Actor un-publishing and re-publishing example
  // -----------------------------------------------------------------------

  actorOf[Consumer4].start // POSTing "stop" to http://0.0.0.0:8877/camel/stop stops and unpublishes this actor
  actorOf[Consumer5].start // POSTing any msg to http://0.0.0.0:8877/camel/start starts and published Consumer4 again.

  // -----------------------------------------------------------------------
  // Active object example
  // -----------------------------------------------------------------------

  // TODO: investigate why this consumer is not published
  TypedActor.typedActorOf(classOf[TypedConsumer1], classOf[TypedConsumer1Impl], defaultConfiguration)
}

/**
 * @author Martin Krasser
 */
class CustomRouteBuilder extends RouteBuilder {
  def configure {
    val actorUri = "actor:%s" format classOf[Consumer2].getName
    from("jetty:http://0.0.0.0:8877/camel/custom").to(actorUri)
    from("direct:welcome").process(new Processor() {
      def process(exchange: Exchange) {
        exchange.getOut.setBody("Welcome %s" format exchange.getIn.getBody)
      }
    })
  }
}
