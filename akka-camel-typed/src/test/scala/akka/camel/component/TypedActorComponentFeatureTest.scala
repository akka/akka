package akka.camel.component

import org.apache.camel._
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.{ DefaultCamelContext, SimpleRegistry }
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec }

import akka.actor.{ Actor, TypedActor }
import akka.actor.TypedActor.Configuration._
import akka.camel._

/**
 * @author Martin Krasser
 */
class TypedActorComponentFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  import TypedActorComponentFeatureTest._
  import CamelContextManager.mandatoryTemplate

  var typedConsumerUuid: String = _

  override protected def beforeAll = {
    val typedActor = TypedActor.typedActorOf(
      classOf[SampleTypedActor],
      classOf[SampleTypedActorImpl], defaultConfiguration) // not a consumer
    val typedConsumer = TypedActor.typedActorOf(
      classOf[SampleTypedConsumer],
      classOf[SampleTypedConsumerImpl], defaultConfiguration)

    typedConsumerUuid = TypedActor.getActorRefFor(typedConsumer).uuid.toString

    val registry = new SimpleRegistry
    // external registration
    registry.put("ta", typedActor)

    CamelContextManager.init(new DefaultCamelContext(registry))
    CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder)
    CamelContextManager.start
  }

  override protected def afterAll = {
    CamelContextManager.stop
    Actor.registry.local.shutdownAll
  }

  feature("Communicate with an internally-registered typed actor using typed-actor-internal endpoint URIs") {
    import TypedActorComponent.InternalSchema
    import ExchangePattern._

    scenario("two-way communication with method returning String") {
      val result1 = mandatoryTemplate.requestBodyAndHeader("%s:%s?method=m2" format (InternalSchema, typedConsumerUuid), "x", "test", "y")
      val result2 = mandatoryTemplate.requestBodyAndHeader("%s:%s?method=m4" format (InternalSchema, typedConsumerUuid), "x", "test", "y")
      assert(result1 === "m2: x y")
      assert(result2 === "m4: x y")
    }

    scenario("two-way communication with method returning void") {
      val result = mandatoryTemplate.requestBodyAndHeader("%s:%s?method=m5" format (InternalSchema, typedConsumerUuid), "x", "test", "y")
      assert(result === "x") // returns initial body
    }

    scenario("one-way communication with method returning String") {
      val result = mandatoryTemplate.send("%s:%s?method=m2" format (InternalSchema, typedConsumerUuid), InOnly, new Processor {
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
      val result = mandatoryTemplate.send("%s:%s?method=m5" format (InternalSchema, typedConsumerUuid), InOnly, new Processor {
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
        mandatoryTemplate.requestBodyAndHeader("typed-actor:%s?method=m2" format typedConsumerUuid, "x", "test", "y")
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
