package se.scalablesolutions.akka.camel.component

import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import org.apache.camel.builder.RouteBuilder
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActorRegistry, ActiveObject}
import se.scalablesolutions.akka.camel._
import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
import org.apache.camel.{ResolveEndpointFailedException, ExchangePattern, Exchange, Processor}

/**
 * @author Martin Krasser
 */
class ActiveObjectComponentFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  import ActiveObjectComponentFeatureTest._
  import CamelContextManager.template

  override protected def beforeAll = {
    val activePojo     = ActiveObject.newInstance(classOf[Pojo]) // not a consumer
    val activePojoBase = ActiveObject.newInstance(classOf[PojoBase])
    val activePojoIntf = ActiveObject.newInstance(classOf[PojoIntf], new PojoImpl)

    val registry = new SimpleRegistry
    registry.put("pojo", activePojo)

    CamelContextManager.init(new DefaultCamelContext(registry))
    CamelContextManager.context.addRoutes(new CustomRouteBuilder)
    CamelContextManager.start

    CamelContextManager.activeObjectRegistry.put("base", activePojoBase)
    CamelContextManager.activeObjectRegistry.put("intf", activePojoIntf)
  }

  override protected def afterAll = {
    CamelContextManager.stop
    ActorRegistry.shutdownAll
  }

  feature("Communicate with an active object from a Camel application using active object endpoint URIs") {
    import ActiveObjectComponent.InternalSchema
    import ExchangePattern._

    scenario("in-out exchange with proxy created from interface and method returning String") {
      val result = template.requestBodyAndHeader("%s:intf?method=m2" format InternalSchema, "x", "test", "y")
      assert(result === "m2impl: x y")
    }

    scenario("in-out exchange with proxy created from class and method returning String") {
      val result = template.requestBodyAndHeader("%s:base?method=m2" format InternalSchema, "x", "test", "y")
      assert(result === "m2base: x y")
    }

    scenario("in-out exchange with proxy created from class and method returning void") {
      val result = template.requestBodyAndHeader("%s:base?method=m5" format InternalSchema, "x", "test", "y")
      assert(result === "x") // returns initial body
    }

    scenario("in-only exchange with proxy created from class and method returning String") {
      val result = template.send("%s:base?method=m2" format InternalSchema, InOnly, new Processor {
        def process(exchange: Exchange) = {
          exchange.getIn.setBody("x")
          exchange.getIn.setHeader("test", "y")
        }
      });
      assert(result.getPattern === InOnly)
      assert(result.getIn.getBody === "m2base: x y")
      assert(result.getOut.getBody === null)
    }

    scenario("in-only exchange with proxy created from class and method returning void") {
      val result = template.send("%s:base?method=m5" format InternalSchema, InOnly, new Processor {
        def process(exchange: Exchange) = {
          exchange.getIn.setBody("x")
          exchange.getIn.setHeader("test", "y")
        }
      });
      assert(result.getPattern === InOnly)
      assert(result.getIn.getBody === "x")
      assert(result.getOut.getBody === null)
    }
  }

  feature("Communicate with an active object from a Camel application from a custom Camel route") {

    scenario("in-out exchange with externally registered active object") {
      val result = template.requestBody("direct:test", "test")
      assert(result === "foo: test")
    }

    scenario("in-out exchange with internally registered active object not possible") {
      intercept[ResolveEndpointFailedException] {
        template.requestBodyAndHeader("active-object:intf?method=m2", "x", "test", "y")
      }
    }
  }
}

object ActiveObjectComponentFeatureTest {
  class CustomRouteBuilder extends RouteBuilder {
    def configure = {
      from("direct:test").to("active-object:pojo?method=foo")
    }
  }
}
