package sample.camel

import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.spring.spi.ApplicationContextRegistry
import org.springframework.context.support.ClassPathXmlApplicationContext

import se.scalablesolutions.akka.camel.{CamelService, CamelContextManager}
import se.scalablesolutions.akka.actor.{ActorRegistry, ActiveObject}

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
