/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps

import org.apache.camel.{ Exchange, Processor }
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint

import akka.camel.TestSupport.SharedCamelSystem
import akka.actor.Props
import akka.pattern._
import scala.concurrent.Await
import scala.concurrent.util.duration._
import org.scalatest._
import akka.testkit._
import matchers.MustMatchers

class UntypedProducerTest extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach with SharedCamelSystem with GivenWhenThen {
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
      val producer = system.actorOf(Props[SampleUntypedReplyingProducer])

      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId -> "123"))
      val future = producer.ask(message)(timeout)

      val expected = CamelMessage("received test", Map(CamelMessage.MessageExchangeId -> "123"))
      Await.result(future, timeout) match {
        case result: CamelMessage ⇒ result must be(expected)
        case unexpected           ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }

    }

    "produce a message and receive a failure response" in {
      val producer = system.actorOf(Props[SampleUntypedReplyingProducer])

      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId -> "123"))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        val future = producer.ask(message)(timeout).failed

        Await.ready(future, timeout).value match {
          case Some(Right(e: AkkaCamelException)) ⇒
            e.getMessage must be("failure")
            e.headers must be(Map(CamelMessage.MessageExchangeId -> "123"))
          case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
        }
      }
    }
  }

  "An UntypedProducer producing a message to a sync Camel route and then forwarding the response" must {

    "produce a message and send a normal response to direct:forward-test-1" in {
      val producer = system.actorOf(Props[SampleUntypedForwardingProducer])

      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(CamelMessage("test", Map[String, Any]()), producer)
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
