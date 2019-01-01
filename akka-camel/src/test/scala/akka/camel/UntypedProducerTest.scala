/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
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
import scala.concurrent.duration._
import org.scalatest._
import akka.testkit._

class UntypedProducerTest extends WordSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with SharedCamelSystem with GivenWhenThen {
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
      val producer = system.actorOf(Props[SampleUntypedReplyingProducer], name = "sample-untyped-replying-producer")

      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId → "123"))
      val future = producer.ask(message)(timeout)

      val expected = CamelMessage("received test", Map(CamelMessage.MessageExchangeId → "123"))
      Await.result(future, timeout) match {
        case result: CamelMessage ⇒ result should ===(expected)
        case unexpected           ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }

    }

    "produce a message and receive a failure response" in {
      val producer = system.actorOf(Props[SampleUntypedReplyingProducer], name = "sample-untyped-replying-producer-failure")

      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId → "123"))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        val future = producer.ask(message)(timeout).failed

        Await.result(future, timeout) match {
          case e: AkkaCamelException ⇒
            e.getMessage should ===("failure")
            e.headers should ===(Map(CamelMessage.MessageExchangeId → "123"))
          case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
        }
      }
    }
  }

  "An UntypedProducer producing a message to a sync Camel route and then forwarding the response" must {

    "produce a message and send a normal response to direct:forward-test-1" in {
      val producer = system.actorOf(Props[SampleUntypedForwardingProducer], name = "sample-untyped-forwarding-producer")

      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(CamelMessage("test", Map[String, Any]()), producer)
      mockEndpoint.assertIsSatisfied
    }

  }

  private def mockEndpoint = camel.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object UntypedProducerTest {
  class TestRoute extends RouteBuilder {
    def configure: Unit = {
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
