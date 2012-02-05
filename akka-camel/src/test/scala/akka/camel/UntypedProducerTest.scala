/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.{ Exchange, Processor }
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint

import akka.camel.TestSupport.SharedCamelSystem
import akka.actor.Props
import akka.dispatch.Await
import akka.util.duration._
import org.scalatest._

class UntypedProducerTest extends WordSpec with BeforeAndAfterAll with BeforeAndAfterEach with SharedCamelSystem with GivenWhenThen {
  import UntypedProducerTest._
  val timeout = 1 second
  override protected def beforeAll = {
    camel.context.addRoutes(new TestRoute)
  }

  override protected def afterEach = {
    mockEndpoint.reset
  }

  "An UntypedProducer producing a message to a sync Camel route" must {

    "produce a message and receive a normal response" in {
      given("a registered two-way producer")
      val producer = system.actorOf(Props[SampleUntypedReplyingProducer])

      when("a test message is sent to the producer with !!")
      val message = Message("test", Map(Message.MessageExchangeId -> "123"))
      val future = producer.ask(message, timeout)
      then("a normal response should have been returned by the producer")
      val expected = Message("received test", Map(Message.MessageExchangeId -> "123"))
      Await.result(future, timeout) match {
        case result: Message ⇒ assert(result === expected)
        case unexpected      ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }

    }

    "produce a message and receive a failure response" in {
      given("a registered two-way producer")
      val producer = system.actorOf(Props[SampleUntypedReplyingProducer])

      when("a test message causing an exception is sent to the producer with !!")
      val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
      val future = producer.ask(message, timeout)
      then("a failure response should have been returned by the producer")
      Await.result(future, timeout) match {
        case result: Failure ⇒ {
          val expectedFailureText = result.cause.getMessage
          val expectedHeaders = result.headers
          assert(expectedFailureText === "failure")
          assert(expectedHeaders === Map(Message.MessageExchangeId -> "123"))
        }
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

  }

  "An UntypedProducer producing a message to a sync Camel route and then forwarding the response" must {

    "produce a message and send a normal response to direct:forward-test-1" in {
      given("a registered one-way producer configured with a forward target")
      val producer = system.actorOf(Props[SampleUntypedForwardingProducer])

      when("a test message is sent to the producer with !")
      mockEndpoint.expectedBodiesReceived("received test")
      val result = producer.tell(Message("test", Map[String, Any]()), producer)

      then("a normal response should have been sent")
      mockEndpoint.assertIsSatisfied
    }

  }

  private def mockEndpoint = camel.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object UntypedProducerTest {
  class TestRoute extends RouteBuilder {
    def configure {
      from("direct:forward-test-1").to("mock:mock")
      from("direct:producer-test-1").process(new Processor() {
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
