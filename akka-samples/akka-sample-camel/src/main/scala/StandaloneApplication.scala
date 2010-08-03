package sample.camel

import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.spring.spi.ApplicationContextRegistry
import org.springframework.context.support.ClassPathXmlApplicationContext

import se.scalablesolutions.akka.actor.{Actor, ActorRegistry, TypedActor}
import se.scalablesolutions.akka.camel._
import se.scalablesolutions.akka.util.Logging

/**
 * @author Martin Krasser
 */
object StandaloneApplication {
  def main(args: Array[String]) {
    import CamelContextManager.context

    // 'externally' register typed actors
    val registry = new SimpleRegistry
    registry.put("sample", TypedActor.newInstance(classOf[BeanIntf], classOf[BeanImpl]))

    // customize CamelContext
    CamelContextManager.init(new DefaultCamelContext(registry))
    CamelContextManager.context.addRoutes(new StandaloneApplicationRoute)

    // start CamelService
    CamelService.start

    // access 'externally' registered typed actors
    assert("hello msg1" == context.createProducerTemplate.requestBody("direct:test", "msg1"))

    // set expectations on upcoming endpoint activation
    val activation = CamelService.expectEndpointActivationCount(1)

    // 'internally' register typed actor (requires CamelService)
    TypedActor.newInstance(classOf[TypedConsumer2], classOf[TypedConsumer2Impl])

    // internal registration is done in background. Wait a bit ...
    activation.await

    // access 'internally' (automatically) registered typed-actors
    // (see @consume annotation value at TypedConsumer2.foo method)
    assert("default: msg3" == context.createProducerTemplate.requestBody("direct:default", "msg3"))

    // shutdown CamelService
    CamelService.stop

    // shutdown all (internally) created actors
    ActorRegistry.shutdownAll
  }
}

class StandaloneApplicationRoute extends RouteBuilder {
  def configure = {
    // route to typed actors (in SimpleRegistry)
    from("direct:test").to("typed-actor:sample?method=foo")
  }
}

object StandaloneSpringApplication {
  def main(args: Array[String]) {
    import CamelContextManager._

    // load Spring application context
    val appctx = new ClassPathXmlApplicationContext("/context-standalone.xml")

    // access 'externally' registered typed actors with typed-actor component
    assert("hello msg3" == template.requestBody("direct:test3", "msg3"))

    // destroy Spring application context
    appctx.close

    // shutdown all (internally) created actors
    ActorRegistry.shutdownAll
  }
}

class StandaloneSpringApplicationRoute extends RouteBuilder {
  def configure = {
    // routes to typed actor (in ApplicationContextRegistry)
    from("direct:test3").to("typed-actor:pojo3?method=foo")
  }
}

object StandaloneJmsApplication {
  def main(args: Array[String]) = {
    val context = new ClassPathXmlApplicationContext("/context-jms.xml")
    val registry = new ApplicationContextRegistry(context)

    // Init CamelContextManager with custom CamelContext
    CamelContextManager.init(new DefaultCamelContext(registry))

    // Start CamelService
    CamelService.start
    // Expect two consumer endpoints to be activated
    val completion = CamelService.expectEndpointActivationCount(2)

    val jmsUri = "jms:topic:test"
    // Wire publisher and consumer using a JMS topic
    val jmsSubscriber1 = Actor.actorOf(new Subscriber("jms-subscriber-1", jmsUri)).start
    val jmsSubscriber2 = Actor.actorOf(new Subscriber("jms-subscriber-2", jmsUri)).start
    val jmsPublisher =   Actor.actorOf(new Publisher("jms-publisher", jmsUri)).start

    // wait for the consumer (subscriber) endpoint being activated
    completion.await

    // Send 10 messages to via publisher actor
    for(i <- 1 to 10) {
      jmsPublisher ! ("Akka rocks (%d)" format i)
    }

    // Send 10 messages to JMS topic directly
    for(i <- 1 to 10) {
      CamelContextManager.template.sendBody(jmsUri, "Camel rocks (%d)" format i)
    }

    // Graceful shutdown of all endpoints/routes
    CamelService.stop

    // Shutdown example actors
    ActorRegistry.shutdownAll
  }
}
