/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel

import language.postfixOps

import org.apache.camel.{ Exchange, Processor }
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import scala.concurrent.Await
import akka.actor.SupervisorStrategy.Stop
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, WordSpecLike }
import akka.actor._
import scala.concurrent.duration._
import akka.util.Timeout
import org.scalatest.Matchers
import akka.testkit._
import akka.actor.Status.Failure

/**
 * Tests the features of the Camel Producer.
 */
class ProducerFeatureTest extends TestKit(ActorSystem("ProducerFeatureTest", AkkaSpec.testConf)) with WordSpecLike with BeforeAndAfterAll with BeforeAndAfterEach with Matchers {

  import ProducerFeatureTest._
  implicit def camel = CamelExtension(system)

  override protected def afterAll(): Unit = {
    super.afterAll()
    shutdown()
  }

  val camelContext = camel.context
  // to make testing equality of messages easier, otherwise the breadcrumb shows up in the result.
  camelContext.setUseBreadcrumb(false)
  val timeoutDuration = 1 second
  implicit val timeout = Timeout(timeoutDuration)
  override protected def beforeAll: Unit = { camelContext.addRoutes(new TestRoute(system)) }

  override protected def afterEach: Unit = { mockEndpoint.reset() }

  "A Producer on a sync Camel route" must {

    "01 produce a message and receive normal response" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-2", true)), name = "01-direct-producer-2")
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId → "123"))
      producer.tell(message, testActor)
      expectMsg(CamelMessage("received TEST", Map(CamelMessage.MessageExchangeId → "123")))
    }

    "02 produce a message and receive failure response" in {
      val latch = TestLatch()
      var deadActor: Option[ActorRef] = None
      val supervisor = system.actorOf(Props(new Actor {
        def receive = {
          case p: Props ⇒ {
            val producer = context.actorOf(p)
            context.watch(producer)
            sender() ! producer
          }
          case Terminated(actorRef) ⇒ {
            deadActor = Some(actorRef)
            latch.countDown()
          }
        }
        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
          case _: AkkaCamelException ⇒ Stop
        }
      }), name = "02-prod-anonymous-supervisor")

      supervisor.tell(Props(new TestProducer("direct:producer-test-2")), testActor)
      val producer = receiveOne(timeoutDuration).asInstanceOf[ActorRef]
      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId → "123"))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        producer.tell(message, testActor)
        expectMsgPF(timeoutDuration) {
          case Failure(e: AkkaCamelException) ⇒
            e.getMessage should ===("failure")
            e.headers should ===(Map(CamelMessage.MessageExchangeId → "123"))
        }
      }
      Await.ready(latch, timeoutDuration)
      deadActor should ===(Some(producer))
    }

    "03 produce a message oneway" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1", true) with Oneway), name = "03-direct-producer-1-oneway")
      mockEndpoint.expectedBodiesReceived("TEST")
      producer ! CamelMessage("test", Map())
      mockEndpoint.assertIsSatisfied()
    }

    "04 produces message twoway without sender reference" in {
      // this test causes a dead letter which can be ignored. The producer is two-way but a oneway tell is used
      // to communicate with it and the response is ignored, which ends up in a dead letter
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-1")), name = "04-ignore-this-deadletter-direct-producer-test-no-sender")
      mockEndpoint.expectedBodiesReceived("test")
      producer ! CamelMessage("test", Map())
      mockEndpoint.assertIsSatisfied()
    }
  }

  "A Producer on an async Camel route" must {

    "10 produce message to direct:producer-test-3 and receive normal response" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")), name = "10-direct-producer-test-3")
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId → "123"))
      producer.tell(message, testActor)
      expectMsg(CamelMessage("received test", Map(CamelMessage.MessageExchangeId → "123")))
    }

    "11 produce message to direct:producer-test-3 and receive failure response" in {
      val producer = system.actorOf(Props(new TestProducer("direct:producer-test-3")), name = "11-direct-producer-test-3-receive-failure")
      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId → "123"))

      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        producer.tell(message, testActor)
        expectMsgPF(timeoutDuration) {
          case Failure(e: AkkaCamelException) ⇒
            e.getMessage should ===("failure")
            e.headers should ===(Map(CamelMessage.MessageExchangeId → "123"))
        }
      }
    }

    "12 produce message, forward normal response of direct:producer-test-2 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget], name = "12-reply-forwarding-target")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)), name = "12-direct-producer-test-2-forwarder")
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId → "123"))
      producer.tell(message, testActor)
      expectMsg(CamelMessage("received test", Map(CamelMessage.MessageExchangeId → "123", "test" → "result")))
    }

    "13 produce message, forward failure response of direct:producer-test-2 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget], name = "13-reply-forwarding-target")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)), name = "13-direct-producer-test-2-forwarder-failure")
      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId → "123"))

      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        producer.tell(message, testActor)
        expectMsgPF(timeoutDuration) {
          case Failure(e: AkkaCamelException) ⇒
            e.getMessage should ===("failure")
            e.headers should ===(Map(CamelMessage.MessageExchangeId → "123", "test" → "failure"))
        }
      }
    }

    "14 produce message, forward normal response to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget], name = "14-producer-forwarding-target")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)), name = "14-direct-producer-test-2-forwarder-to-producing-target")
      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(CamelMessage("test", Map()), producer)
      mockEndpoint.assertIsSatisfied()
    }

    "15 produce message, forward failure response to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget], name = "15-producer-forwarding-target-failure")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-2", target)), name = "15-direct-producer-test-2-forward-failure")
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        mockEndpoint.expectedMessageCount(1)
        mockEndpoint.message(0).body().isInstanceOf(classOf[akka.actor.Status.Failure])
        producer.tell(CamelMessage("fail", Map()), producer)
        mockEndpoint.assertIsSatisfied()
      }
    }

    "16 produce message, forward normal response from direct:producer-test-3 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget], name = "16-reply-forwarding-target")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)), name = "16-direct-producer-test-3-to-replying-actor")
      val message = CamelMessage("test", Map(CamelMessage.MessageExchangeId → "123"))

      producer.tell(message, testActor)
      expectMsg(CamelMessage("received test", Map(CamelMessage.MessageExchangeId → "123", "test" → "result")))
    }

    "17 produce message, forward failure response from direct:producer-test-3 to a replying target actor and receive response" in {
      val target = system.actorOf(Props[ReplyingForwardTarget], name = "17-reply-forwarding-target")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)), name = "17-direct-producer-test-3-forward-failure")

      val message = CamelMessage("fail", Map(CamelMessage.MessageExchangeId → "123"))
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        producer.tell(message, testActor)
        expectMsgPF(timeoutDuration) {
          case Failure(e: AkkaCamelException) ⇒
            e.getMessage should ===("failure")
            e.headers should ===(Map(CamelMessage.MessageExchangeId → "123", "test" → "failure"))
        }
      }
    }

    "18 produce message, forward normal response from direct:producer-test-3 to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget], "18-producing-forward-target-normal")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)), name = "18-direct-producer-test-3-forward-normal")
      mockEndpoint.expectedBodiesReceived("received test")
      producer.tell(CamelMessage("test", Map()), producer)
      mockEndpoint.assertIsSatisfied()
    }

    "19 produce message, forward failure response from direct:producer-test-3 to a producing target actor and produce response to direct:forward-test-1" in {
      val target = system.actorOf(Props[ProducingForwardTarget], "19-producing-forward-target-failure")
      val producer = system.actorOf(Props(new TestForwarder("direct:producer-test-3", target)), name = "19-direct-producer-test-3-forward-failure-producing-target")
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        mockEndpoint.expectedMessageCount(1)
        mockEndpoint.message(0).body().isInstanceOf(classOf[akka.actor.Status.Failure])
        producer.tell(CamelMessage("fail", Map()), producer)
        mockEndpoint.assertIsSatisfied()
      }
    }

    "20 keep producing messages after error" in {
      import TestSupport._
      val consumer = start(new IntermittentErrorConsumer("direct:intermittentTest-1"), "20-intermittentTest-error-consumer")
      val producer = start(new SimpleProducer("direct:intermittentTest-1"), "20-intermittentTest-producer")
      filterEvents(EventFilter[AkkaCamelException](occurrences = 1)) {
        val futureFailed = producer.tell("fail", testActor)
        expectMsgPF(timeoutDuration) {
          case Failure(e) ⇒
            e.getMessage should ===("fail")
        }
        producer.tell("OK", testActor)
        expectMsg("OK")
      }
      stop(consumer)
      stop(producer)
    }

    "21 be able to transform outgoing messages and have a valid sender reference" in {
      import TestSupport._
      filterEvents(EventFilter[Exception](occurrences = 1)) {
        val producerSupervisor = system.actorOf(Props(new ProducerSupervisor(Props(new ChildProducer("mock:mock", true)))), "21-ignore-deadletter-sender-ref-test")
        mockEndpoint.reset()
        producerSupervisor.tell(CamelMessage("test", Map()), testActor)
        producerSupervisor.tell(CamelMessage("err", Map()), testActor)
        mockEndpoint.expectedMessageCount(1)
        mockEndpoint.expectedBodiesReceived("TEST")
        expectMsg("TEST")
      }
    }
  }

  private def mockEndpoint = camel.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ProducerFeatureTest {

  class ProducerSupervisor(childProps: Props) extends Actor {
    override def supervisorStrategy = SupervisorStrategy.stoppingStrategy
    val child = context.actorOf(childProps, "producer-supervisor-child")
    val duration = 10 seconds
    implicit val timeout = Timeout(duration)
    implicit val ec = context.system.dispatcher
    Await.ready(CamelExtension(context.system).activationFutureFor(child), timeout.duration)
    def receive = {
      case msg: CamelMessage ⇒
        child forward (msg)
      case (aref: ActorRef, msg: String) ⇒
        aref ! msg
    }
  }

  class ChildProducer(uri: String, upper: Boolean = false) extends Actor with Producer {
    override def oneway = true

    var lastSender: Option[ActorRef] = None
    var lastMessage: Option[String] = None
    def endpointUri = uri

    override def transformOutgoingMessage(msg: Any) = msg match {
      case msg: CamelMessage ⇒ if (upper) msg.mapBody {
        body: String ⇒
          if (body == "err") throw new Exception("Crash!")
          val upperMsg = body.toUpperCase
          lastSender = Some(sender())
          lastMessage = Some(upperMsg)
      }
      else msg
    }

    override def postStop(): Unit = {
      for (msg ← lastMessage; aref ← lastSender) context.parent ! ((aref, msg))
      super.postStop()
    }
  }

  class TestProducer(uri: String, upper: Boolean = false) extends Actor with Producer {
    def endpointUri = uri

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      //overriding on purpose so it doesn't try to deRegister and reRegister at restart,
      // which would cause a deadletter message in the test output.
    }

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
        case "fail" ⇒ context.sender() ! akka.actor.Status.Failure(new AkkaCamelException(new Exception("failure"), msg.headers))
        case _ ⇒
          context.sender() ! (msg.mapBody {
            body: String ⇒ "received %s" format body
          })
      }
    }
  }

  class ReplyingForwardTarget extends Actor {
    def receive = {
      case msg: CamelMessage ⇒
        context.sender() ! (msg.copy(headers = msg.headers + ("test" → "result")))
      case msg: akka.actor.Status.Failure ⇒
        msg.cause match {
          case e: AkkaCamelException ⇒ context.sender() ! Status.Failure(new AkkaCamelException(e, e.headers + ("test" → "failure")))
        }
    }
  }

  class ProducingForwardTarget extends Actor with Producer with Oneway {
    def endpointUri = "direct:forward-test-1"
  }

  class TestRoute(system: ActorSystem) extends RouteBuilder {
    val responder = system.actorOf(Props[TestResponder], name = "TestResponder")

    def configure: Unit = {
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

  class SimpleProducer(override val endpointUri: String) extends Producer {
    override protected def transformResponse(msg: Any) = msg match {
      case m: CamelMessage ⇒ m.bodyAs[String]
      case m: Any          ⇒ m
    }
  }

  class IntermittentErrorConsumer(override val endpointUri: String) extends Consumer {
    def receive = {
      case msg: CamelMessage if msg.bodyAs[String] == "fail" ⇒ sender() ! Failure(new Exception("fail"))
      case msg: CamelMessage                                 ⇒ sender() ! msg
    }
  }

}
