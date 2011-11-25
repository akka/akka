/**
  * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>.
  */

// CAMEL IS NOT PART OF MILESTONE 1 OF AKKA 2.0

//package sample.camel
//
//import org.apache.camel.{ Exchange, Processor }
//import org.apache.camel.builder.RouteBuilder
//import org.apache.camel.impl.DefaultCamelContext
//import org.apache.camel.spring.spi.ApplicationContextRegistry
//import org.springframework.context.support.ClassPathXmlApplicationContext
//
//import akka.actor.Actor._
//import akka.actor.Props
//import akka.actor.TypedActor
//import akka.camel.CamelContextManager
//
///**
// * @author Martin Krasser
// */
//class Boot {
//
//  // -----------------------------------------------------------------------
//  // Basic example
//  // -----------------------------------------------------------------------
//
//  actorOf[Consumer1]
//  actorOf[Consumer2]
//
//  // -----------------------------------------------------------------------
//  // Custom Camel route example
//  // -----------------------------------------------------------------------
//
//  // Create CamelContext and a Spring-based registry
//  val context = new ClassPathXmlApplicationContext("/context-jms.xml", getClass)
//  val registry = new ApplicationContextRegistry(context)
//
//  // Use a custom Camel context and a custom touter builder
//  CamelContextManager.init(new DefaultCamelContext(registry))
//  CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder)
//
//  val producer = actorOf[Producer1]
//  val mediator = actorOf(new Transformer(producer))
//  val consumer = actorOf(new Consumer3(mediator))
//
//  // -----------------------------------------------------------------------
//  // Asynchronous consumer-producer example (Akka homepage transformation)
//  // -----------------------------------------------------------------------
//
//  val httpTransformer = actorOf(new HttpTransformer)
//  val httpProducer = actorOf(new HttpProducer(httpTransformer))
//  val httpConsumer = actorOf(new HttpConsumer(httpProducer))
//
//  // -----------------------------------------------------------------------
//  // Publish subscribe examples
//  // -----------------------------------------------------------------------
//
//  //
//  // Cometd example commented out because camel-cometd is broken since Camel 2.3
//  //
//
//  //val cometdUri = "cometd://localhost:8111/test/abc?baseResource=file:target"
//  //val cometdSubscriber = actorOf(new Subscriber("cometd-subscriber", cometdUri))
//  //val cometdPublisher = actorOf(new Publisher("cometd-publisher", cometdUri))
//
//  val jmsUri = "jms:topic:test"
//  val jmsSubscriber1 = actorOf(new Subscriber("jms-subscriber-1", jmsUri))
//  val jmsSubscriber2 = actorOf(new Subscriber("jms-subscriber-2", jmsUri))
//  val jmsPublisher = actorOf(new Publisher(jmsUri), "jms-publisher")
//
//  //val cometdPublisherBridge = actorOf(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/cometd", cometdPublisher))
//  val jmsPublisherBridge = actorOf(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/jms", jmsPublisher))
//
//  // -----------------------------------------------------------------------
//  // Actor un-publishing and re-publishing example
//  // -----------------------------------------------------------------------
//
//  actorOf[Consumer4] // POSTing "stop" to http://0.0.0.0:8877/camel/stop stops and unpublishes this actor
//  actorOf[Consumer5] // POSTing any msg to http://0.0.0.0:8877/camel/start starts and published Consumer4 again.
//
//  // -----------------------------------------------------------------------
//  // Active object example
//  // -----------------------------------------------------------------------
//
//  // TODO: investigate why this consumer is not published
//  TypedActor.typedActorOf(classOf[TypedConsumer1], classOf[TypedConsumer1Impl], Props())
//}
//
///**
// * @author Martin Krasser
// */
//class CustomRouteBuilder extends RouteBuilder {
//  def configure {
//    val actorUri = "actor:%s" format classOf[Consumer2].getName
//    from("jetty:http://0.0.0.0:8877/camel/custom").to(actorUri)
//    from("direct:welcome").process(new Processor() {
//      def process(exchange: Exchange) {
//        exchange.getOut.setBody("Welcome %s" format exchange.getIn.getBody)
//      }
//    })
//  }
//}
