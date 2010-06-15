package sample.camel

import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
import org.apache.camel.builder.RouteBuilder

import se.scalablesolutions.akka.camel.{CamelService, CamelContextManager}
import se.scalablesolutions.akka.actor.{ActorRegistry, ActiveObject}

/**
 * @author Martin Krasser
 */
object PlainApplication {
  def main(args: Array[String]) {
    import CamelContextManager.context

    // 'externally' register active objects
    val registry = new SimpleRegistry
    registry.put("pojo1", ActiveObject.newInstance(classOf[BeanIntf], new BeanImpl))
    registry.put("pojo2", ActiveObject.newInstance(classOf[BeanImpl]))

    // customize CamelContext
    CamelContextManager.init(new DefaultCamelContext(registry))
    CamelContextManager.context.addRoutes(new PlainApplicationRoute)

    // start CamelService
    val camelService = CamelService.newInstance
    camelService.load

    // 'internally' register active object (requires CamelService)
    ActiveObject.newInstance(classOf[ConsumerPojo2])

    // access 'externally' registered active objects with active-object component
    assert("hello msg1" == context.createProducerTemplate.requestBody("direct:test1", "msg1"))
    assert("hello msg2" == context.createProducerTemplate.requestBody("direct:test2", "msg2"))

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

class PlainApplicationRoute extends RouteBuilder {
  def configure = {
    from("direct:test1").to("active-object:pojo1?method=foo")
    from("direct:test2").to("active-object:pojo2?method=foo")
  }
}

object SpringApplication {
  // TODO
}
