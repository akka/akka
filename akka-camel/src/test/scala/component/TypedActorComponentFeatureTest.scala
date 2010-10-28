package akka.camel.component

import org.apache.camel._
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import akka.actor.{ActorRegistry, TypedActor}
import akka.camel._

/**
 * @author Martin Krasser
 */
class TypedActorComponentFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  import TypedActorComponentFeatureTest._
  import CamelContextManager.mandatoryTemplate

  override protected def beforeAll = {
    val typedActor     = TypedActor.newInstance(classOf[SampleTypedActor], classOf[SampleTypedActorImpl]) // not a consumer
    val typedConsumer  = TypedActor.newInstance(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl])

    val registry = new SimpleRegistry
    // external registration
    registry.put("ta", typedActor)

    CamelContextManager.init(new DefaultCamelContext(registry))
    CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder)
    CamelContextManager.start

    // Internal registration
    CamelContextManager.typedActorRegistry.put("tc", typedConsumer)
  }

  override protected def afterAll = {
    CamelContextManager.stop
    ActorRegistry.shutdownAll
  }

  feature("Communicate with an internally-registered typed actor using typed-actor-internal endpoint URIs") {
    import TypedActorComponent.InternalSchema
    import ExchangePattern._

    scenario("two-way communication with method returning String") {
      val result1 = mandatoryTemplate.requestBodyAndHeader("%s:tc?method=m2" format InternalSchema, "x", "test", "y")
      val result2 = mandatoryTemplate.requestBodyAndHeader("%s:tc?method=m4" format InternalSchema, "x", "test", "y")
      assert(result1 === "m2: x y")
      assert(result2 === "m4: x y")
    }

    scenario("two-way communication with method returning void") {
      val result = mandatoryTemplate.requestBodyAndHeader("%s:tc?method=m5" format InternalSchema, "x", "test", "y")
      assert(result === "x") // returns initial body
    }

    scenario("one-way communication with method returning String") {
      val result = mandatoryTemplate.send("%s:tc?method=m2" format InternalSchema, InOnly, new Processor {
        def process(exchange: Exchange) = {
          exchange.getIn.setBody("x")
          exchange.getIn.setHeader("test", "y")
        }
      });
      assert(result.getPattern === InOnly)
      assert(result.getIn.getBody === "m2: x y")
      assert(result.getOut.getBody === null)
    }

    scenario("one-way communication with method returning void") {
      val result = mandatoryTemplate.send("%s:tc?method=m5" format InternalSchema, InOnly, new Processor {
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

  feature("Communicate with an internally-registered typed actor using typed-actor endpoint URIs") {
    scenario("communication not possible") {
      intercept[ResolveEndpointFailedException] {
        mandatoryTemplate.requestBodyAndHeader("typed-actor:tc?method=m2", "x", "test", "y")
      }
    }
  }

  feature("Communicate with an externally-registered typed actor using typed-actor endpoint URIs") {
    scenario("two-way communication with method returning String") {
      val result = mandatoryTemplate.requestBody("typed-actor:ta?method=foo", "test")
      assert(result === "foo: test")
    }

    scenario("two-way communication with method returning String via custom route") {
      val result = mandatoryTemplate.requestBody("direct:test", "test")
      assert(result === "foo: test")
    }
  }
}

object TypedActorComponentFeatureTest {
  class CustomRouteBuilder extends RouteBuilder {
    def configure = {
      from("direct:test").to("typed-actor:ta?method=foo")
    }
  }
}
