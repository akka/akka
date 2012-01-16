package akka.camel

import org.apache.camel.{Exchange, Processor}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import akka.actor.Actor._

class UntypedProducerFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach with GivenWhenThen {
  import UntypedProducerFeatureTest._

  override protected def beforeAll = {
    registry.shutdownAll
    CamelContextManager.init
    CamelContextManager.mandatoryContext.addRoutes(new TestRoute)
    CamelContextManager.start
  }

  override protected def afterAll = {
    CamelContextManager.stop
    registry.shutdownAll
  }

  override protected def afterEach = {
    mockEndpoint.reset
  }

  feature("Produce a message to a sync Camel route") {

    scenario("produce message and receive normal response") {
      given("a registered two-way producer")
      val producer = actorOf(classOf[SampleUntypedReplyingProducer])
      producer.start

      when("a test message is sent to the producer with !!")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = producer.sendRequestReply(message)

      then("a normal response should have been returned by the producer")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123"))
      assert(result === expected)
    }

    scenario("produce message and receive failure response") {
      given("a registered two-way producer")
      val producer = actorOf(classOf[SampleUntypedReplyingProducer])
      producer.start

      when("a test message causing an exception is sent to the producer with !!")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = producer.sendRequestReply(message).asInstanceOf[Failure]

      then("a failure response should have been returned by the producer")
      val expectedFailureText = result.cause.getMessage
      val expectedHeaders = result.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
    }

  }

  feature("Produce a message to a sync Camel route and then forward the response") {

    scenario("produce message and send normal response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val producer = actorOf(classOf[SampleUntypedForwardingProducer])
      producer.start

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("received test")
      val result = producer.sendOneWay(Message("test"), producer)

      then("a normal response should have been sent")
      mockEndpoint.assertIsSatisfied
    }

  }

  private def mockEndpoint = CamelContextManager.mandatoryContext.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object UntypedProducerFeatureTest {
  class TestRoute extends RouteBuilder {
    def configure {
      from("direct:forward-test-1").to("mock:mock")
      from("direct:producer-test-1").process(new Processor() {
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
