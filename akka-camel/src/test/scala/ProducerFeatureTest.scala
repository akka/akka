package se.scalablesolutions.akka.camel

import org.apache.camel.{Exchange, Processor}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.{Actor, ActorRegistry}
import se.scalablesolutions.akka.actor.Actor._

class ProducerFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach with GivenWhenThen {
  import ProducerFeatureTest._

  override protected def beforeAll = {
    ActorRegistry.shutdownAll
    CamelContextManager.init
    CamelContextManager.context.addRoutes(new TestRoute)
    CamelContextManager.start
  }

  override protected def afterAll = CamelContextManager.stop

  override protected def afterEach = {
    mockEndpoint.reset
    ActorRegistry.shutdownAll
  }

  feature("Produce a message to a Camel endpoint") {

    scenario("produce message and receive response") {
      given("a registered asynchronous two-way producer for endpoint direct:producer-test-2")
      val producer = actorOf(new TestProducer("direct:producer-test-2"))
      producer.start

      when("a test message is sent to the producer")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = producer !! message

      then("the expected result message should be returned including a correlation identifier")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123"))
      assert(result === Some(expected))
    }

    scenario("produce message and receive failure") {
      given("a registered asynchronous two-way producer for endpoint direct:producer-test-2")
      val producer = actorOf(new TestProducer("direct:producer-test-2"))
      producer.start

      when("a fail message is sent to the producer")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer !! message).as[Failure]

      then("the expected failure message should be returned including a correlation identifier")
      val expectedFailureText = result.get.cause.getMessage
      val expectedHeaders = result.get.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
    }

    scenario("produce message oneway") {
      given("a registered asynchronous one-way producer for endpoint direct:producer-test-1")
      val producer = actorOf(new TestProducer("direct:producer-test-1") with Oneway)
      producer.start

      when("a test message is sent to the producer")
      mockEndpoint.expectedBodiesReceived("test")
      producer ! Message("test")

      then("the expected message should have been sent to mock:mock")
      mockEndpoint.assertIsSatisfied
    }

    scenario("produce message twoway without sender reference") {
      given("a registered asynchronous two-way producer for endpoint direct:producer-test-1")
      val producer = actorOf(new TestProducer("direct:producer-test-1"))
      producer.start

      when("a test message is sent to the producer")
      mockEndpoint.expectedBodiesReceived("test")
      producer ! Message("test")

      then("there should be only a warning that there's no sender reference")
      mockEndpoint.assertIsSatisfied
    }
  }

  private def mockEndpoint = CamelContextManager.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ProducerFeatureTest {
  class TestProducer(uri: String) extends Actor with Producer {
    def endpointUri = uri
  }

  class TestRoute extends RouteBuilder {
    def configure {
      // for one-way messaging tests
      from("direct:producer-test-1").to("mock:mock")
      // for two-way messaging tests
      from("direct:producer-test-2").process(new Processor() {
        def process(exchange: Exchange) = {
          exchange.getIn.getBody match {
            case "fail" => throw new Exception("failure")
            case body   => exchange.getOut.setBody("received %s" format body)
          }
        }
      })
    }
  }
}