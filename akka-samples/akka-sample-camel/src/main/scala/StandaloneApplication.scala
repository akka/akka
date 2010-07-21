package sample.camel

import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.spring.spi.ApplicationContextRegistry
import org.springframework.context.support.ClassPathXmlApplicationContext

import se.scalablesolutions.akka.actor.{Actor, ActorRegistry, ActiveObject}
import se.scalablesolutions.akka.camel._
import se.scalablesolutions.akka.util.Logging

/**
 * @author Martin Krasser
 */
object StandaloneApplication {
  def main(args: Array[String]) {
    import CamelContextManager.context

    // 'externally' register active objects
    val registry = new SimpleRegistry
    registry.put("pojo1", ActiveObject.newInstance(classOf[BeanIntf], new BeanImpl))
    registry.put("pojo2", ActiveObject.newInstance(classOf[BeanImpl]))

    // customize CamelContext
    CamelContextManager.init(new DefaultCamelContext(registry))
    CamelContextManager.context.addRoutes(new StandaloneApplicationRoute)

    // start CamelService
    val camelService = CamelService.newInstance.load

    // access 'externally' registered active objects
    assert("hello msg1" == context.createProducerTemplate.requestBody("direct:test1", "msg1"))
    assert("hello msg2" == context.createProducerTemplate.requestBody("direct:test2", "msg2"))

    // 'internally' register active object (requires CamelService)
    ActiveObject.newInstance(classOf[ConsumerPojo2])

    // internal registration is done in background. Wait a bit ...
    Thread.sleep(1000)

    // access 'internally' (automatically) registered active-objects
    // (see @consume annotation value at ConsumerPojo2.foo method)
    assert("default: msg3" == context.createProducerTemplate.requestBody("direct:default", "msg3"))

    // shutdown CamelService
    camelService.unload

    // shutdown all (internally) created actors
    ActorRegistry.shutdownAll
  }
}

class StandaloneApplicationRoute extends RouteBuilder {
  def configure = {
    // routes to active objects (in SimpleRegistry)
    from("direct:test1").to("active-object:pojo1?method=foo")
    from("direct:test2").to("active-object:pojo2?method=foo")
  }
}

object StandaloneSpringApplication {
  def main(args: Array[String]) {
    import CamelContextManager._

    // load Spring application context
    val appctx = new ClassPathXmlApplicationContext("/context-standalone.xml")

    // access 'externally' registered active objects with active-object component
    assert("hello msg3" == template.requestBody("direct:test3", "msg3"))

    // destroy Spring application context
    appctx.close

    // shutdown all (internally) created actors
    ActorRegistry.shutdownAll
  }
}

class StandaloneSpringApplicationRoute extends RouteBuilder {
  def configure = {
    // routes to active object (in ApplicationContextRegistry)
    from("direct:test3").to("active-object:pojo3?method=foo")
  }
}

object StandaloneJmsApplication {
  def main(args: Array[String]) = {
    val context = new ClassPathXmlApplicationContext("/context-jms.xml")
    val registry = new ApplicationContextRegistry(context)

    // Init CamelContextManager with custom CamelContext
    CamelContextManager.init(new DefaultCamelContext(registry))

    // Create new instance of CamelService and start it
    val service = CamelService.newInstance.load
    // Expect two consumer endpoints to be activated
    val completion = service.expectEndpointActivationCount(2)

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
    service.unload

    // Shutdown example actors
    ActorRegistry.shutdownAll
  }
}
