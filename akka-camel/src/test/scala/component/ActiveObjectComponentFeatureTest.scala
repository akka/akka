package se.scalablesolutions.akka.camel.component

import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.camel._
import se.scalablesolutions.akka.actor.{ActorRegistry, ActiveObject}
import org.apache.camel.{ExchangePattern, Exchange, Processor}

/**
 * @author Martin Krasser
 */
class ActiveObjectComponentFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  override protected def beforeAll = {
    val activePojoBase = ActiveObject.newInstance(classOf[PojoBase])
    val activePojoIntf = ActiveObject.newInstance(classOf[PojoIntf], new PojoImpl)

    CamelContextManager.init
    CamelContextManager.start

    CamelContextManager.activeObjectRegistry.put("base", activePojoBase)
    CamelContextManager.activeObjectRegistry.put("intf", activePojoIntf)
  }

  override protected def afterAll = {
    CamelContextManager.stop
    ActorRegistry.shutdownAll
  }

  feature("Communicate with an active object from a Camel application using active object endpoint URIs") {
    import ActiveObjectComponent.DefaultSchema
    import CamelContextManager.template
    import ExchangePattern._

    scenario("in-out exchange with proxy created from interface and method returning String") {
      val result = template.requestBodyAndHeader("%s:intf?method=m2" format DefaultSchema, "x", "test", "y")
      assert(result === "m2impl: x y")
    }

    scenario("in-out exchange with proxy created from class and method returning String") {
      val result = template.requestBodyAndHeader("%s:base?method=m2" format DefaultSchema, "x", "test", "y")
      assert(result === "m2base: x y")
    }

    scenario("in-out exchange with proxy created from class and method returning void") {
      val result = template.requestBodyAndHeader("%s:base?method=m5" format DefaultSchema, "x", "test", "y")
      assert(result === "x") // returns initial body
    }

    scenario("in-only exchange with proxy created from class and method returning String") {
      val result = template.send("%s:base?method=m2" format DefaultSchema, InOnly, new Processor {
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
      val result = template.send("%s:base?method=m5" format DefaultSchema, InOnly, new Processor {
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
}
