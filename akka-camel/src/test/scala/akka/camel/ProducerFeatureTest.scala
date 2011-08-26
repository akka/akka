package akka.camel

import org.apache.camel.{ Exchange, Processor }
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{ GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec }

import akka.actor.Actor._
import akka.actor.{ ActorRef, Actor }

class ProducerFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach with GivenWhenThen {
  import ProducerFeatureTest._

  override protected def beforeAll = {
    Actor.registry.local.shutdownAll
    CamelContextManager.init
    CamelContextManager.mandatoryContext.addRoutes(new TestRoute)
    CamelContextManager.start
  }

  override protected def afterAll = {
    CamelContextManager.stop
    Actor.registry.local.shutdownAll
  }

  override protected def afterEach = {
    mockEndpoint.reset
  }

  feature("Produce a message to a sync Camel route") {

    scenario("produce message and receive normal response") {
      given("a registered two-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-2", true))
      producer.start

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).get

      then("a normal response should have been returned by the producer")
      val expected = Message("received TEST", Map(Message.MessageExchangeId -> "123"))
      assert(result === expected)
    }

    scenario("produce message and receive failure response") {
      given("a registered two-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-2"))
      producer.start

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).as[Failure]

      then("a failure response should have been returned by the producer")
      val expectedFailureText = result.get.cause.getMessage
      val expectedHeaders = result.get.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
    }

    scenario("produce message oneway") {
      given("a registered one-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-1", true) with Oneway)
      producer.start

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("TEST")
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

  feature("Produce a message to an async Camel route") {

    scenario("produce message and receive normal response") {
      given("a registered two-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-3"))
      producer.start

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).as[Message].get

      then("a normal response should have been returned by the producer")
      assert(result.headers(Message.MessageExchangeId) === "123")
    }

    scenario("produce message and receive failure response") {
      given("a registered two-way producer")
      val producer = actorOf(new TestProducer("direct:producer-test-3"))
      producer.start

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).as[Failure]

      then("a failure response should have been returned by the producer")
      val expectedFailureText = result.get.cause.getMessage
      val expectedHeaders = result.get.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
    }
  }

  feature("Produce a message to a sync Camel route and then forward the response") {

    scenario("produce message, forward normal response to a replying target actor and receive response") {
      given("a registered two-way producer configured with a forward target")
      val target = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-2", target)).start

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).get

      then("a normal response should have been returned by the forward target")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123", "test" -> "result"))
      assert(result === expected)
    }

    scenario("produce message, forward failure response to a replying target actor and receive response") {
      given("a registered two-way producer configured with a forward target")
      val target = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-2", target)).start

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).as[Failure].get

      then("a failure response should have been returned by the forward target")
      val expectedFailureText = result.cause.getMessage
      val expectedHeaders = result.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123", "test" -> "failure"))
    }

    scenario("produce message, forward normal response to a producing target actor and produce response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val target = actorOf[ProducingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-2", target)).start

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("received test")
      val result = producer.!(Message("test"))(Some(producer))

      then("a normal response should have been produced by the forward target")
      mockEndpoint.assertIsSatisfied
    }

    scenario("produce message, forward failure response to a producing target actor and produce response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val target = actorOf[ProducingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-2", target)).start

      when("a test message causing an exception is sent to the producer with !")
      mockEndpoint.expectedMessageCount(1)
      mockEndpoint.message(0).body().isInstanceOf(classOf[Failure])
      val result = producer.!(Message("fail"))(Some(producer))

      then("a failure response should have been produced by the forward target")
      mockEndpoint.assertIsSatisfied
    }
  }

  feature("Produce a message to an async Camel route and then forward the response") {

    scenario("produce message, forward normal response to a replying target actor and receive response") {
      given("a registered two-way producer configured with a forward target")
      val target = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-3", target)).start

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).as[Message].get

      then("a normal response should have been returned by the forward target")
      assert(result.headers(Message.MessageExchangeId) === "123")
      assert(result.headers("test") === "result")
    }

    scenario("produce message, forward failure response to a replying target actor and receive response") {
      given("a registered two-way producer configured with a forward target")
      val target = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-3", target)).start

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer ? message).as[Failure]

      then("a failure response should have been returned by the forward target")
      val expectedFailureText = result.get.cause.getMessage
      val expectedHeaders = result.get.headers
      assert(expectedFailureText === "failure")
      assert(expectedHeaders === Map(Message.MessageExchangeId -> "123", "test" -> "failure"))
    }

    scenario("produce message, forward normal response to a producing target actor and produce response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val target = actorOf[ProducingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-3", target)).start

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("received test")
      val result = producer.!(Message("test"))(Some(producer))

      then("a normal response should have been produced by the forward target")
      mockEndpoint.assertIsSatisfied
    }

    scenario("produce message, forward failure response to a producing target actor and produce response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val target = actorOf[ProducingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-3", target)).start

      when("a test message causing an exception is sent to the producer with !")
      mockEndpoint.expectedMessageCount(1)
      mockEndpoint.message(0).body().isInstanceOf(classOf[Failure])
      val result = producer.!(Message("fail"))(Some(producer))

      then("a failure response should have been produced by the forward target")
      mockEndpoint.assertIsSatisfied
    }
  }

  private def mockEndpoint = CamelContextManager.mandatoryContext.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ProducerFeatureTest {
  class TestProducer(uri: String, upper: Boolean = false) extends Actor with Producer {
    def endpointUri = uri
    override protected def receiveBeforeProduce = {
      case msg: Message ⇒ if (upper) msg.transformBody { body: String ⇒ body.toUpperCase } else msg
    }
  }

  class TestForwarder(uri: String, target: ActorRef) extends Actor with Producer {
    def endpointUri = uri
    override protected def receiveAfterProduce = {
      case msg ⇒ target forward msg
    }
  }

  class TestResponder extends Actor {
    protected def receive = {
      case msg: Message ⇒ msg.body match {
        case "fail" ⇒ self.reply(Failure(new Exception("failure"), msg.headers))
        case _      ⇒ self.reply(msg.transformBody { body: String ⇒ "received %s" format body })
      }
    }
  }

  class ReplyingForwardTarget extends Actor {
    protected def receive = {
      case msg: Message ⇒
        self.reply(msg.addHeader("test" -> "result"))
      case msg: Failure ⇒
        self.reply(Failure(msg.cause, msg.headers + ("test" -> "failure")))
    }
  }

  class ProducingForwardTarget extends Actor with Producer with Oneway {
    def endpointUri = "direct:forward-test-1"
  }

  class TestRoute extends RouteBuilder {
    val responder = actorOf[TestResponder].start
    def configure {
      from("direct:forward-test-1").to("mock:mock")
      // for one-way messaging tests
      from("direct:producer-test-1").to("mock:mock")
      // for two-way messaging tests (async)
      from("direct:producer-test-3").to("actor:uuid:%s" format responder.uuid)
      // for two-way messaging tests (sync)
      from("direct:producer-test-2").process(new Processor() {
        def process(exchange: Exchange) = {
          exchange.getIn.getBody match {
            case "fail" ⇒ throw new Exception("failure")
            case body   ⇒ exchange.getOut.setBody("received %s" format body)
          }
        }
      })
    }
  }
}
