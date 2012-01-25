/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.{ Exchange, Processor }
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{ GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec }
import akka.actor._
import akka.dispatch.Await
import akka.util.duration._
import akka.camel.TestSupport.SharedCamelSystem

/**
 * Tests the features of the Camel Producer.
 */
class ProducerFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach with SharedCamelSystem with GivenWhenThen {

  import ProducerFeatureTest._

  val camelContext = camel.context
  // to make testing equality of messages easier, otherwise the breadcrumb shows up in the result.
  camelContext.setUseBreadcrumb(false)

  val timeout = 1 second

  override protected def beforeAll { camelContext.addRoutes(new TestRoute(system)) }

  override protected def afterEach { mockEndpoint.reset() }

  feature("Producer on a sync Camel route") {

    scenario("produces a message and receives normal response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-2", true)))
      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, timeout)
      then("a normal response must have been returned by the producer")
      val expected = Message("received TEST", Map(Message.MessageExchangeId -> "123"), camelContext)
      Await.result(future, timeout) match {
        case result: Message ⇒ assert(result === expected)
        case unexpected      ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces a message and receives failure response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-2")))

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, timeout)
      Await.result(future, timeout) match {
        case result: Failure ⇒ {
          then("a failure response must have been returned by the producer")
          val expectedFailureText = result.cause.getMessage
          val expectedHeaders = result.headers
          assert(expectedFailureText === "failure")
          assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces message oneway") {
      given("a registered one-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1", true) with Oneway))

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("TEST")
      producer ! Message("test", Map(), camelContext)

      then("the test message must have been sent to mock:mock")
      mockEndpoint.assertIsSatisfied()
    }

    scenario("produces message twoway without sender reference") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1")))

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("test")
      producer ! Message("test", Map(), camelContext)

      then("there must be only a warning that there's no sender reference")
      mockEndpoint.assertIsSatisfied()
    }
  }

  feature("Producer on an async Camel route") {

    scenario("produces message to direct:producer-test-3 and receives normal response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")))

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, timeout)

      Await.result(future, timeout) match {
        case result: Message ⇒ {
          then("a normal response must have been returned by the producer")
          val expected = Message("received test", Map(Message.MessageExchangeId -> "123"), camelContext)
          assert(result === expected)
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces message to direct:producer-test-3 and receives failure response") {
      given("a registered two-way producer")
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")))

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, timeout)
      Await.result(future, timeout) match {
        case result: Failure ⇒ {
          then("a failure response must have been returned by the producer")
          val expectedFailureText = result.cause.getMessage
          val expectedHeaders = result.headers
          assert(expectedFailureText === "failure")
          assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces message, forwards normal response of direct:producer-test-2 to a replying target actor and receives response") {
      given("a registered two-way producer configured with a forward target")
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, timeout)

      Await.result(future, timeout) match {
        case result: Message ⇒ {
          then("a normal response must have been returned by the forward target")
          val expected = Message("received test", Map(Message.MessageExchangeId -> "123", "test" -> "result"), camelContext)
          assert(result === expected)
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces message, forwards failure response of direct:producer-test-2 to a replying target actor and receives response") {
      given("a registered two-way producer configured with a forward target")
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))

      when("a test message causing an exception is sent to the producer with ?")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, timeout)
      Await.result(future, timeout) match {
        case failure: Failure ⇒ {
          then("a failure response must have been returned by the forward target")
          val expectedFailureText = failure.cause.getMessage
          val expectedHeaders = failure.headers
          assert(expectedFailureText === "failure")
          assert(expectedHeaders === Map(Message.MessageExchangeId -> "123", "test" -> "failure"))
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces message, forwards normal response to a producing target actor and produces response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(Message("test", Map(), camelContext), producer)

      then("a normal response must have been produced by the forward target")
      mockEndpoint.assertIsSatisfied()
    }

    scenario("produces message, forwards failure response to a producing target actor and produces response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")

      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))

      when("a test message causing an exception is sent to the producer with !")
      mockEndpoint.expectedMessageCount(1)
      mockEndpoint.message(0).body().isInstanceOf(classOf[Failure])
      producer.tell(Message("fail", Map(), camelContext), producer)

      then("a failure response must have been produced by the forward target")
      mockEndpoint.assertIsSatisfied()
    }

    scenario("produces message, forwards normal response from direct:producer-test-3 to a replying target actor and receives response") {
      given("a registered two-way producer configured with a forward target")
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))

      when("a test message is sent to the producer with ?")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"), camelContext)

      val future = producer ? (message, timeout)

      then("a normal response must have been returned by the forward target")
      Await.result(future, timeout) match {
        case message: Message ⇒ {
          val expected = Message("received test", Map(Message.MessageExchangeId -> "123", "test" -> "result"), camelContext)
          assert(message === expected)
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces message, forwards failure response from direct:producer-test-3 to a replying target actor and receives response") {
      given("a registered two-way producer configured with a forward target")
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))

      when("a test message causing an exception is sent to the producer with !!")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"), camelContext)
      val future = producer ? (message, timeout)
      Await.result(future, timeout) match {
        case failure: Failure ⇒ {
          then("a failure response must have been returned by the forward target")
          val expectedFailureText = failure.cause.getMessage
          val expectedHeaders = failure.headers
          assert(expectedFailureText === "failure")
          assert(expectedHeaders === Map(Message.MessageExchangeId -> "123", "test" -> "failure"))
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    scenario("produces message, forwards normal response from direct:producer-test-3 to a producing target actor and produces response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(Message("test", Map(), camelContext), producer)

      then("a normal response must have been produced by the forward target")
      mockEndpoint.assertIsSatisfied()
    }

    scenario("produces message, forwards failure response from direct:producer-test-3 to a producing target actor and produces response to direct:forward-test-1") {
      given("a registered one-way producer configured with a forward target")
      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))

      when("a test message causing an exception is sent to the producer with !")
      mockEndpoint.expectedMessageCount(1)
      mockEndpoint.message(0).body().isInstanceOf(classOf[Failure])
      producer.tell(Message("fail", Map(), camelContext), producer)

      then("a failure response must have been produced by the forward target")
      mockEndpoint.assertIsSatisfied()
    }
  }

  private def mockEndpoint = camel.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ProducerFeatureTest {

  class TestProducer(uri: String, upper: Boolean = false) extends Actor with Producer {
    def endpointUri = uri

    override protected def receiveBeforeProduce = {
      case msg: Message ⇒ if (upper) msg.transformBody {
        body: String ⇒ body.toUpperCase
      }
      else msg
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
        case "fail" ⇒ {
          context.sender ! (Failure(new Exception("failure"), msg.headers))
        }
        case bod: Any ⇒ {
          context.sender ! (msg.transformBody {
            body: String ⇒ "received %s" format body
          })
        }
      }
    }
  }

  class ReplyingForwardTarget extends Actor {
    protected def receive = {
      case msg: Message ⇒
        context.sender ! (msg.addHeader("test" -> "result"))
      case msg: Failure ⇒
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
            case "fail" ⇒ throw new Exception("failure")
            case body   ⇒ exchange.getOut.setBody("received %s" format body)
          }
        }
      })
    }
  }

}
