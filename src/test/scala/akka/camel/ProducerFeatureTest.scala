package akka.camel

import org.apache.camel.{Exchange, Processor}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}
import akka.actor._
import akka.dispatch.Await
import akka.util.duration._

class ProducerFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach with GivenWhenThen {

  import ProducerFeatureTest._

  val system = akka.actor.ActorSystem.create("ProducerFeatureTest")
  val camel = CamelExtension(system)
  val camelContext = camel.context

  override protected def beforeAll = {
    camelContext.addRoutes(new TestRoute(system))
  }

  override protected def afterAll = {
    system.shutdown()
  }

  override protected def afterEach = {
    mockEndpoint.reset
  }

  feature("Produce a message to a sync Camel route") {

    scenario("produce message and receive normal response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-2", true)))
      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ?(message, 1 second)
      then("a normal response should have been returned by the producer")
      val expected = Message("received TEST", Map(Message.MessageExchangeId -> "123"), camelContext)
      Await.result(future, 1 second) match {
        case result: Message => assert(result === expected)
        case fail:Failure => throw fail.getCause
      }

    }
    scenario("produce message and receive failure response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-2")))

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, 1 second)
      Await.result(future, 1 second) match {
        case result:Failure => {
          then("a failure response should have been returned by the producer")
          val expectedFailureText = result.cause.getMessage
          val expectedHeaders = result.headers
          assert(expectedFailureText === "failure")
          assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
        }
        case _ => fail("should have returned a Failure")
      }
    }

    scenario("produce message oneway") {
      given("a registered one-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1", true) with Oneway))

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("TEST")
      producer ! Message("test", Map(), camelContext)

      then("the test message should have been sent to mock:mock")
      mockEndpoint.assertIsSatisfied()
    }

    scenario("produce message twoway without sender reference") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1")))

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("test")
      producer ! Message("test", Map(), camelContext)

      then("there should be only a warning that there's no sender reference")
      mockEndpoint.assertIsSatisfied()
    }
  }

  feature("Produce a message to an async Camel route") {

    scenario("produce message and receive normal response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")))

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, 5 second)

      Await.result(future, 5 second) match {
        case messageResult:MessageResult => {
          val result= messageResult.message
          then("a normal response should have been returned by the producer")
          val expected = Message("received test", Map(Message.MessageExchangeId -> "123"), camelContext)
          assert(result === expected)
        }
        case fail:Failure => throw fail.getCause
      }
    }

    scenario("produce message and receive failure response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")))

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, 5 second)
      Await.result(future, 5 second) match {
        case result:FailureResult => {
          then("a failure response should have been returned by the producer")
          val expectedFailureText = result.failure.cause.getMessage
          val expectedHeaders = result.failure.headers
          assert(expectedFailureText === "failure")
          assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
        }
        case m:Any => fail ("should have responded with Failure:"+m)
      }
    }
  /*
  }
  feature("Produce a message to a sync Camel route and then forward the response") {

    scenario("produce message, forward normal response to a replying target actor and receive response") {
      given("a registered two-way producer configured with a forward target")
      val target = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-2", target)).start

      when("a test message is sent to the producer with !!")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = producer !! message

      then("a normal response should have been returned by the forward target")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123", "test" -> "result"))
      assert(result === Some(expected))
    }

    scenario("produce message, forward failure response to a replying target actor and receive response") {
      given("a registered two-way producer configured with a forward target")
      val target = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-2", target)).start

      when("a test message causing an exception is sent to the producer with !!")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer !! message).as[Failure]

      then("a failure response should have been returned by the forward target")
      val expectedFailureText = result.get.cause.getMessage
      val expectedHeaders = result.get.headers
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

      when("a test message is sent to the producer with !!")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val result = producer !! message

      then("a normal response should have been returned by the forward target")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123", "test" -> "result"))
      assert(result === Some(expected))
    }

    scenario("produce message, forward failure response to a replying target actor and receive response") {
      given("a registered two-way producer configured with a forward target")
      val target = actorOf[ReplyingForwardTarget].start
      val producer = actorOf(new TestForwarder("direct:producer-test-3", target)).start

      when("a test message causing an exception is sent to the producer with !!")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val result = (producer !! message).as[Failure]

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
    */
  }

  private def mockEndpoint = camel.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ProducerFeatureTest {

  class TestProducer(uri: String, upper: Boolean = false) extends Actor with Producer {
    def endpointUri = uri

    override protected def receiveBeforeProduce = {
      case msg: Message => if (upper) msg.transformBody {
        body: String => body.toUpperCase
      } else msg
    }
  }

  class TestForwarder(uri: String, target: ActorRef) extends Actor with Producer {
    def endpointUri = uri

    override protected def receiveAfterProduce = {
      case msg => target forward msg
    }
  }

  class TestResponder extends Actor {
    protected def receive = {
      case msg: Message => msg.body match {
        case "fail" => {
          context.sender ! (Failure(new Exception("failure"), msg.headers))
        }
        case bod:Any => {
          context.sender ! (msg.transformBody {
            body: String => "received %s" format body
          })
        }
      }
    }
  }

  class ReplyingForwardTarget extends Actor {
    protected def receive = {
      case msg: Message =>
        context.sender ! (msg.addHeader("test" -> "result"))
      case msg: Failure =>
        context.sender ! (Failure(msg.cause, msg.headers + ("test" -> "failure")))
    }
  }

  class ProducingForwardTarget extends Actor with Producer with Oneway {
    def endpointUri = "direct:forward-test-1"
  }

  class TestRoute(system: ActorSystem) extends RouteBuilder {
    val responder = system.actorOf(Props[TestResponder], name = "TestResponder")

    def configure {
      from("direct:forward-test-1").to("mock:mock")
      // for one-way messaging tests
      from("direct:producer-test-1").to("mock:mock")
      // for two-way messaging tests (async)
      from("direct:producer-test-3").to(responder)
      // for two-way messaging tests (sync)
      from("direct:producer-test-2").process(new Processor() {
        def process(exchange: Exchange) = {
          exchange.getIn.getBody match {
            case "fail" => throw new Exception("failure")
            case body => exchange.getOut.setBody("received %s" format body)
          }
        }
      })
    }
  }

}
