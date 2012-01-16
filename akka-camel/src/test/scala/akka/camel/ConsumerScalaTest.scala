package akka.camel

import java.util.concurrent.{ TimeoutException, CountDownLatch, TimeUnit }

import org.apache.camel.{ AsyncProcessor, AsyncCallback, CamelExecutionException }
import org.apache.camel.builder.Builder
import org.apache.camel.component.direct.DirectEndpoint
import org.apache.camel.model.RouteDefinition
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor._
import akka.actor._

/**
 * @author Martin Krasser
 */
class ConsumerScalaTest extends WordSpec with BeforeAndAfterAll with MustMatchers {
  import CamelContextManager.mandatoryContext
  import CamelContextManager.mandatoryTemplate
  import ConsumerScalaTest._

  var service: CamelService = null

  override protected def beforeAll = {
    registry.local.shutdownAll
    service = CamelServiceFactory.createCamelService
    // register test consumer before registering the publish requestor
    // and before starting the CamelService (registry is scanned for consumers)
    actorOf(Props(new TestConsumer("direct:publish-test-1"))
    service.registerPublishRequestor
    service.awaitEndpointActivation(1) {
      service.start
    } must be(true)
  }

  override protected def afterAll = {
    service.stop
    registry.local.shutdownAll
  }

  "A responding consumer" when {
    var consumer: ActorRef = null
    "started before starting the CamelService" must {
      "support an in-out message exchange via its endpoint" in {
        mandatoryTemplate.requestBody("direct:publish-test-1", "msg1") must equal("received msg1")
      }
    }
    "not started" must {
      "not have an associated endpoint in the CamelContext" in {
        CamelContextManager.mandatoryContext.hasEndpoint("direct:publish-test-2") must be(null)
      }
    }
    "started" must {
      "support an in-out message exchange via its endpoint" in {
        service.awaitEndpointActivation(1) {
          consumer = actorOf(Props(new TestConsumer("direct:publish-test-2"))
        } must be(true)
        mandatoryTemplate.requestBody("direct:publish-test-2", "msg2") must equal("received msg2")
      }
      "have an associated endpoint in the CamelContext" in {
        CamelContextManager.mandatoryContext.hasEndpoint("direct:publish-test-2") must not be (null)
      }
    }
    "stopped" must {
      "not support an in-out message exchange via its endpoint" in {
        service.awaitEndpointDeactivation(1) {
          consumer.stop
        } must be(true)
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBody("direct:publish-test-2", "msg2")
        }
      }
    }
  }

  "A responding, untyped consumer" when {
    var consumer: ActorRef = null
    "started" must {
      "support an in-out message exchange via its endpoint" in {
        service.awaitEndpointActivation(1) {
          consumer = Actor.actorOf(classOf[SampleUntypedConsumer])
        } must be(true)
        mandatoryTemplate.requestBodyAndHeader("direct:test-untyped-consumer", "x", "test", "y") must equal("x y")
      }
    }
    "stopped" must {
      "not support an in-out message exchange via its endpoint" in {
        service.awaitEndpointDeactivation(1) {
          consumer.stop
        } must be(true)
        intercept[CamelExecutionException] {
          mandatoryTemplate.sendBodyAndHeader("direct:test-untyped-consumer", "blah", "test", "blub")
        }
      }
    }
  }

  "A non-responding, blocking consumer" when {
    "receiving an in-out message exchange" must {
      "lead to a TimeoutException" in {
        service.awaitEndpointActivation(1) {
          actorOf(Props(creator = () ⇒ new TestBlocker("direct:publish-test-5")))
        } must be(true)

        try {
          mandatoryTemplate.requestBody("direct:publish-test-5", "msg3")
          fail("expected TimoutException not thrown")
        } catch {
          case e ⇒ {
            assert(e.getCause.isInstanceOf[TimeoutException])
          }
        }
      }
    }
  }

  "A responding, blocking consumer" when {
    "activated with a custom error handler" must {
      "handle thrown exceptions by generating a custom response" in {
        service.awaitEndpointActivation(1) {
          actorOf(Props[ErrorHandlingConsumer]
        } must be(true)
        mandatoryTemplate.requestBody("direct:error-handler-test", "hello") must equal("error: hello")

      }
    }
    "activated with a custom redelivery handler" must {
      "handle thrown exceptions by redelivering the initial message" in {
        service.awaitEndpointActivation(1) {
          actorOf(Props[RedeliveringConsumer]
        } must be(true)
        mandatoryTemplate.requestBody("direct:redelivery-test", "hello") must equal("accepted: hello")

      }
    }
  }

  "An non auto-acknowledging consumer" when {
    "started" must {
      "must support acknowledgements on system level" in {

        var consumer: ActorRef = null

        service.awaitEndpointActivation(1) {
          consumer = actorOf(Props(new TestAckConsumer("direct:system-ack-test"))
        } must be(true)

        val endpoint = mandatoryContext.getEndpoint("direct:system-ack-test", classOf[DirectEndpoint])
        val producer = endpoint.createProducer.asInstanceOf[AsyncProcessor]
        val exchange = endpoint.createExchange

        val latch = new CountDownLatch(1)
        val handler = new AsyncCallback {
          def done(doneSync: Boolean) = {
            doneSync must be(false)
            latch.countDown
          }
        }

        exchange.getIn.setBody("test")
        producer.process(exchange, handler)

        latch.await(5, TimeUnit.SECONDS) must be(true)
        consumer.stop
      }
    }
  }

  "A supervised consumer" must {
    "be able to reply during receive" in {
      val consumer = Actor.actorOf(Props(new SupervisedConsumer("reply-channel-test-1"))
      (consumer ? "succeed").get must equal("ok")
    }

    "be able to reply on failure during preRestart" in {
      val consumer = Actor.actorOf(Props(new SupervisedConsumer("reply-channel-test-2"))
      val supervisor = Supervisor(
        SupervisorConfig(
          OneForOneStrategy(List(classOf[Exception]), 2, 10000),
          Supervise(consumer, Permanent) :: Nil))

      val latch = new CountDownLatch(1)
      val sender = Actor.actorOf(Props(new Sender("pr", latch))

      consumer.!("fail")(Some(sender))
      latch.await(5, TimeUnit.SECONDS) must be(true)
    }

    "be able to reply on failure during postStop" in {
      val consumer = Actor.actorOf(Props(new SupervisedConsumer("reply-channel-test-3")))
      val supervisor = Supervisor(
        SupervisorConfig(
          OneForOneStrategy(List(classOf[Exception]), Some(0)),
          Supervise(consumer, Temporary) :: Nil))

      val latch = new CountDownLatch(1)
      val sender = Actor.actorOf(Props(new Sender("ps", latch))

      consumer.!("fail")(Some(sender))
      latch.await(5, TimeUnit.SECONDS) must be(true)
    }
  }
}

object ConsumerScalaTest {
  trait BlockingConsumer extends Consumer { self: Actor ⇒
    override def blocking = true
  }

  class TestConsumer(uri: String) extends Actor with Consumer {
    def endpointUri = uri
    protected def receive = {
      case msg: Message ⇒ sender ! "received %s" format msg.body
    }
  }

  class TestBlocker(uri: String) extends Actor with BlockingConsumer {
    def endpointUri = uri
    protected def receive = {
      case msg: Message ⇒ { /* do not reply */ }
    }
  }

  class TestAckConsumer(uri: String) extends Actor with Consumer {
    def endpointUri = uri
    override def autoack = false
    protected def receive = {
      case msg: Message ⇒ sender ! Ack
    }
  }

  class ErrorHandlingConsumer extends Actor with BlockingConsumer {
    def endpointUri = "direct:error-handler-test"

    onRouteDefinition { rd: RouteDefinition ⇒
      rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
    }

    protected def receive = {
      case msg: Message ⇒ throw new Exception("error: %s" format msg.body)
    }
  }

  class SupervisedConsumer(name: String) extends Actor with Consumer {
    def endpointUri = "direct:%s" format name

    protected def receive = {
      case "fail"    ⇒ { throw new Exception("test") }
      case "succeed" ⇒ sender ! "ok"
    }

    override def preRestart(reason: scala.Throwable, msg: Option[Any]) {
      sender.tell("pr")
    }

    override def postStop {
      sender.tell("ps")
    }
  }

  class Sender(expected: String, latch: CountDownLatch) extends Actor {
    def receive = {
      case msg if (msg == expected) ⇒ latch.countDown
      case _                        ⇒ {}
    }
  }

  class RedeliveringConsumer extends Actor with BlockingConsumer {
    def endpointUri = "direct:redelivery-test"

    onRouteDefinition { rd: RouteDefinition ⇒
      rd.onException(classOf[Exception]).maximumRedeliveries(1).end
    }

    //
    // first message to this actor is not valid and will be rejected
    //

    var valid = false

    protected def receive = {
      case msg: Message ⇒ try {
        respondTo(msg)
      } finally {
        valid = true
      }
    }

    private def respondTo(msg: Message) =
      if (valid) sender ! ("accepted: %s" format msg.body)
      else throw new Exception("rejected: %s" format msg.body)

  }
}
