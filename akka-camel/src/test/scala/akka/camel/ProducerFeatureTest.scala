/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps

import org.apache.camel.{ Exchange, Processor }
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import scala.concurrent.Await
import akka.camel.TestSupport.SharedCamelSystem
import akka.actor.SupervisorStrategy.Stop
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, WordSpec }
import akka.actor._
import akka.pattern._
import scala.concurrent.util.duration._
import akka.util.Timeout
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import scala.util.Success

/**
 * Tests the features of the Camel Producer.
 */
class ProducerFeatureTest extends WordSpec with BeforeAndAfterAll with BeforeAndAfterEach with SharedCamelSystem with MustMatchers {

  import ProducerFeatureTest._

  val camelContext = camel.context
  // to make testing equality of messages easier, otherwise the breadcrumb shows up in the result.
  camelContext.setUseBreadcrumb(false)

  val timeoutDuration = 1 second
  implicit val timeout = Timeout(timeoutDuration)
  override protected def beforeAll { camelContext.addRoutes(new TestRoute(system)) }

  override protected def afterEach { mockEndpoint.reset() }

  "A Producer on a sync Camel route" must {
    "produce a message and receive normal response" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-2", true)))
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId -> "123"))
      val future = producer.ask(message)(timeoutDuration)
      val expected = CamelMessage("received TEST", Map(CamelMessage.MessageExchangeId -> "123"))
      Await.result(future, timeoutDuration) match {
        case result: CamelMessage ⇒ assert(result === expected)
        case unexpected           ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    "produce a message and receive failure response" in {
      val latch = TestLatch()
      var deadActor: Option[ActorRef] = None
      val supervisor = system.actorOf(Props(new Actor {
        def receive = {
          case p: Props ⇒ {
            val producer = context.actorOf(p)
            context.watch(producer)
            sender ! producer
          }
          case Terminated(actorRef) ⇒ {
            deadActor = Some(actorRef)
            latch.countDown()
          }
        }
        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
          case _: AkkaCamelException ⇒ Stop
        }
      }))
      val producer = Await.result[ActorRef](supervisor.ask(Props(new TestProducer("direct:producer-test-2"))).mapTo[ActorRef], timeoutDuration)
      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId -> "123"))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        val e = intercept[AkkaCamelException] { Await.result(producer.ask(message)(timeoutDuration), timeoutDuration) }
        e.getMessage must be("failure")
        e.headers must be(Map(CamelMessage.MessageExchangeId -> "123"))
      }
      Await.ready(latch, timeoutDuration)
      deadActor must be(Some(producer))
    }

    "produce a message oneway" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1", true) with Oneway))
      mockEndpoint.expectedBodiesReceived("TEST")
      producer ! CamelMessage("test", Map())
      mockEndpoint.assertIsSatisfied()
    }

    "produces message twoway without sender reference" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1")))
      mockEndpoint.expectedBodiesReceived("test")
      producer ! CamelMessage("test", Map())
      mockEndpoint.assertIsSatisfied()
    }
  }

  "A Producer on an async Camel route" must {

    "produce message to direct:producer-test-3 and receive normal response" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")))
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId -> "123"))
      val future = producer.ask(message)(timeoutDuration)

      Await.result(future, timeoutDuration) match {
        case result: CamelMessage ⇒
          // a normal response must have been returned by the producer
          val expected = CamelMessage("received test", Map(CamelMessage.MessageExchangeId -> "123"))
          result must be(expected)
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    "produce message to direct:producer-test-3 and receive failure response" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")))
      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId -> "123"))

      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        val e = intercept[AkkaCamelException] { Await.result(producer.ask(message)(timeoutDuration), timeoutDuration) }
        e.getMessage must be("failure")
        e.headers must be(Map(CamelMessage.MessageExchangeId -> "123"))
      }
    }

    "produce message, forward normal response of direct:producer-test-2 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId -> "123"))
      val future = producer.ask(message)(timeoutDuration)

      Await.result(future, timeoutDuration) match {
        case result: CamelMessage ⇒
          // a normal response must have been returned by the forward target
          val expected = CamelMessage("received test", Map(CamelMessage.MessageExchangeId -> "123", "test" -> "result"))
          result must be(expected)
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    "produce message, forward failure response of direct:producer-test-2 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))
      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId -> "123"))

      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        val e = intercept[AkkaCamelException] { Await.result(producer.ask(message)(timeoutDuration), timeoutDuration) }
        e.getMessage must be("failure")
        e.headers must be(Map(CamelMessage.MessageExchangeId -> "123", "test" -> "failure"))
      }
    }

    "produce message, forward normal response to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))
      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(CamelMessage("test", Map()), producer)
      mockEndpoint.assertIsSatisfied()
    }

    "produce message, forward failure response to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        mockEndpoint.expectedMessageCount(1)
        mockEndpoint.message(0).body().isInstanceOf(classOf[akka.actor.Status.Failure])
        producer.tell(CamelMessage("fail", Map()), producer)
        mockEndpoint.assertIsSatisfied()
      }
    }

    "produce message, forward normal response from direct:producer-test-3 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId -> "123"))

      val future = producer.ask(message)(timeoutDuration)
      Await.result(future, timeoutDuration) match {
        case message: CamelMessage ⇒
          val expected = CamelMessage("received test", Map(CamelMessage.MessageExchangeId -> "123", "test" -> "result"))
          message must be(expected)
        case unexpected ⇒ fail("Actor responded with unexpected message:" + unexpected)
      }
    }

    "produce message, forward failure response from direct:producer-test-3 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))

      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId -> "123"))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        val e = intercept[AkkaCamelException] { Await.result(producer.ask(message)(timeoutDuration), timeoutDuration) }
        e.getMessage must be("failure")
        e.headers must be(Map(CamelMessage.MessageExchangeId -> "123", "test" -> "failure"))
      }
    }

    "produce message, forward normal response from direct:producer-test-3 to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))
      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(CamelMessage("test", Map()), producer)
      mockEndpoint.assertIsSatisfied()
    }

    "produce message, forward failure response from direct:producer-test-3 to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget])
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        mockEndpoint.expectedMessageCount(1)
        mockEndpoint.message(0).body().isInstanceOf(classOf[akka.actor.Status.Failure])
        producer.tell(CamelMessage("fail", Map()), producer)
        mockEndpoint.assertIsSatisfied()
      }
    }
  }

  private def mockEndpoint = camel.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ProducerFeatureTest {

  class TestProducer(uri: String, upper: Boolean = false) extends Actor with Producer {
    def endpointUri = uri

    override protected def transformOutgoingMessage(msg: Any) = msg match {
      case msg: CamelMessage ⇒ if (upper) msg.mapBody {
        body: String ⇒ body.toUpperCase
      }
      else msg
    }
  }

  class TestForwarder(uri: String, target: ActorRef) extends Actor with Producer {
    def endpointUri = uri

    override def headersToCopy = Set(CamelMessage.MessageExchangeId, "test")

    override def routeResponse(msg: Any): Unit = target forward msg
  }

  class TestResponder extends Actor {
    def receive = {
      case msg: CamelMessage ⇒ msg.body match {
        case "fail" ⇒ context.sender ! akka.actor.Status.Failure(new AkkaCamelException(new Exception("failure"), msg.headers))
        case _ ⇒
          context.sender ! (msg.mapBody {
            body: String ⇒ "received %s" format body
          })
      }
    }
  }

  class ReplyingForwardTarget extends Actor {
    def receive = {
      case msg: CamelMessage ⇒
        context.sender ! (msg.addHeader("test" -> "result"))
      case msg: akka.actor.Status.Failure ⇒
        msg.cause match {
          case e: AkkaCamelException ⇒ context.sender ! Status.Failure(new AkkaCamelException(e, e.headers + ("test" -> "failure")))
        }
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
