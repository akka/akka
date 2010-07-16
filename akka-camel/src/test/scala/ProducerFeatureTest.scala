package se.scalablesolutions.akka.camel

import org.apache.camel.{Exchange, Processor}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActorRef, Actor, ActorRegistry}

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

    scenario("produce message and receive normal response") {
      given("a registered two-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-2"))
      producer.start

      when("a test message is sent to the producer with !!")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = producer !! message

      then("a normal response should have been returned by the producer")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123"))
      assert(result === Some(expected))
    }

    scenario("produce message and receive failure response") {
      given("a registered two-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-2"))
      producer.start

      when("a test message causing an exception is sent to the producer with !!")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer !! message).as[Failure]

      then("a failure response should have been returned by the producer")
      val expectedFailureText = result.get.cause.getMessage
      val expectedHeaders = result.get.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
    }

    scenario("produce message oneway") {
      given("a registered one-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-1") with Oneway)
      producer.start

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("test")
      producer ! Message("test")

      then("the test message should have been sent to mock:mock")
      mockEndpoint.assertIsSatisfied
    }

    scenario("produce message twoway without sender reference") {
      given("a registered two-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-1"))
      producer.start

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("test")
      producer ! Message("test")

      then("there should be only a warning that there's no sender reference")
      mockEndpoint.assertIsSatisfied
    }
  }

  feature("Produce a message to a Camel endpoint and then forward the result") {

    scenario("produce message, forward and receive normal response") {
      given("a registered two-way producer configured with a forward target")
      val responder = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestProducer("direct:producer-test-2", Some(responder))).start

      when("a test message is sent to the producer with !!")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = producer !! message

      then("a normal response should have been returned by the forward target")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123", "test" -> "result"))
      assert(result === Some(expected))
    }

    scenario("produce message, forward and receive failure response") {
      given("a registered two-way producer configured with a forward target")
      val responder = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestProducer("direct:producer-test-2", Some(responder))).start

      when("a test message causing an exception is sent to the producer with !!")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer !! message).as[Failure]

      then("a failure response should have been returned by the forward target")
      val expectedFailureText = result.get.cause.getMessage
      val expectedHeaders = result.get.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123", "test" -> "failure"))
    }

    scenario("produce message, forward and produce normal response") {
      given("a registered one-way producer configured with a forward target")
      val responder = actorOf[ProducingForwardTarget].start
      val producer = actorOf(new TestProducer("direct:producer-test-2", Some(responder))).start

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("received test")
      val result = producer ! Message("test")

      then("a normal response should have been produced by the forward target")
      mockEndpoint.assertIsSatisfied
    }

    scenario("produce message, forward and produce failure response") {
      given("a registered one-way producer configured with a forward target")
      val responder = actorOf[ProducingForwardTarget].start
      val producer = actorOf(new TestProducer("direct:producer-test-2", Some(responder))).start

      when("a test message causing an exception is sent to the producer with !")
      mockEndpoint.expectedMessageCount(1)
      mockEndpoint.message(0).body().isInstanceOf(classOf[Failure])
      val result = producer ! Message("fail")

      then("a failure response should have been produced by the forward target")
      mockEndpoint.assertIsSatisfied
    }
  }

  private def mockEndpoint = CamelContextManager.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ProducerFeatureTest {
  class TestProducer(uri: String, target: Option[ActorRef] = None) extends Actor with Producer {
    def endpointUri = uri
    override def forwardResultTo = target
  }

  class ReplyingForwardTarget extends Actor {
    protected def receive = {
      case msg: Message =>
        self.reply(msg.addHeader("test" -> "result"))
      case msg: Failure =>
        self.reply(Failure(msg.cause, msg.headers + ("test" -> "failure")))
    }
  }

  class ProducingForwardTarget extends Actor with Producer with Oneway {
    def endpointUri = "direct:forward-test-1"
  }

  class TestRoute extends RouteBuilder {
    def configure {
      from("direct:forward-test-1").to("mock:mock")
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