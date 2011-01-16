package akka.camel

import java.util.concurrent.{TimeoutException, CountDownLatch, TimeUnit}

import org.apache.camel.CamelExecutionException
import org.apache.camel.builder.Builder
import org.apache.camel.model.RouteDefinition
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor._
import akka.actor._

/**
 * @author Martin Krasser
 */
class ConsumerScalaTest extends WordSpec with BeforeAndAfterAll with MustMatchers {
  import CamelContextManager.mandatoryTemplate
  import ConsumerScalaTest._

  var service: CamelService = _

  override protected def beforeAll = {
    registry.shutdownAll
    // create new CamelService instance
    service = CamelServiceFactory.createCamelService
    // register test consumer before registering the publish requestor
    // and before starting the CamelService (registry is scanned for consumers)
    actorOf(new TestConsumer("direct:publish-test-1")).start
    // Register publish requestor as listener
    service.registerPublishRequestor
    // start consumer publisher, otherwise we cannot set message
    // count expectations in the next step (needed for testing only).
    service.consumerPublisher.start
    service.awaitEndpointActivation(1) {
      service.start
    } must be (true)
  }

  override protected def afterAll = {
    service.stop
    registry.shutdownAll
  }

  "A responding consumer" when {
    val consumer = actorOf(new TestConsumer("direct:publish-test-2"))
    "started before starting the CamelService" must {
      "support an in-out message exchange via its endpoint" in {
        mandatoryTemplate.requestBody("direct:publish-test-1", "msg1") must equal ("received msg1")
      }
    }
    "not started" must {
      "not have an associated endpoint in the CamelContext" in {
        CamelContextManager.mandatoryContext.hasEndpoint("direct:publish-test-2") must be (null)
      }
    }
    "started" must {
      "support an in-out message exchange via its endpoint" in {
        service.awaitEndpointActivation(1) {
          consumer.start
        } must be (true)
        mandatoryTemplate.requestBody("direct:publish-test-2", "msg2") must equal ("received msg2")
      }
      "have an associated endpoint in the CamelContext" in {
        CamelContextManager.mandatoryContext.hasEndpoint("direct:publish-test-2") must not be (null)
      }
    }
    "stopped" must {
      "not support an in-out message exchange via its endpoint" in {
        service.awaitEndpointDeactivation(1) {
          consumer.stop
        } must be (true)
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBody("direct:publish-test-2", "msg2")
        }
      }
    }
  }

  "A responding, typed consumer" when {
    var actor: SampleTypedConsumer = null
    "started" must {
      "support in-out message exchanges via its endpoints" in {
        service.awaitEndpointActivation(3) {
          actor = TypedActor.newInstance(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl])
        } must be (true)
        mandatoryTemplate.requestBodyAndHeader("direct:m2", "x", "test", "y") must equal ("m2: x y")
        mandatoryTemplate.requestBodyAndHeader("direct:m3", "x", "test", "y") must equal ("m3: x y")
        mandatoryTemplate.requestBodyAndHeader("direct:m4", "x", "test", "y") must equal ("m4: x y")
      }
    }
    "stopped" must {
      "not support in-out message exchanges via its endpoints" in {
        service.awaitEndpointDeactivation(3) {
          TypedActor.stop(actor)
        } must be (true)
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBodyAndHeader("direct:m2", "x", "test", "y")
        }
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBodyAndHeader("direct:m3", "x", "test", "y")
        }
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBodyAndHeader("direct:m4", "x", "test", "y")
        }
      }
    }
  }

  "A responding, typed consumer (Scala)" when {
    var actor: TestTypedConsumer = null
    "started" must {
      "support in-out message exchanges via its endpoints" in {
        service.awaitEndpointActivation(2) {
          actor = TypedActor.newInstance(classOf[TestTypedConsumer], classOf[TestTypedConsumerImpl])
        } must be (true)
        mandatoryTemplate.requestBody("direct:publish-test-3", "x") must equal ("foo: x")
        mandatoryTemplate.requestBody("direct:publish-test-4", "x") must equal ("bar: x")
      }
    }
    "stopped" must {
      "not support in-out message exchanges via its endpoints" in {
        service.awaitEndpointDeactivation(2) {
          TypedActor.stop(actor)
        } must be (true)
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBody("direct:publish-test-3", "x")
        }
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBody("direct:publish-test-4", "x")
        }
      }
    }
  }

  "A responding, untyped consumer" when {
    val consumer = Actor.actorOf(classOf[SampleUntypedConsumer])
    "started" must {
      "support an in-out message exchange via its endpoint" in {
        service.awaitEndpointActivation(1) {
          consumer.start
        } must be (true)
        mandatoryTemplate.requestBodyAndHeader("direct:test-untyped-consumer", "x", "test", "y") must equal ("x y")
      }
    }
    "stopped" must {
      "not support an in-out message exchange via its endpoint" in {
        service.awaitEndpointDeactivation(1) {
          consumer.stop
        } must be (true)
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
          actorOf(new TestBlocker("direct:publish-test-5")).start
        } must be (true)

        try {
          mandatoryTemplate.requestBody("direct:publish-test-5", "msg3")
          fail("expected TimoutException not thrown")
        } catch {
          case e => {
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
          actorOf[ErrorHandlingConsumer].start
        } must be (true)
        mandatoryTemplate.requestBody("direct:error-handler-test", "hello") must equal ("error: hello")

      }
    }
    "activated with a custom redelivery handler" must {
      "handle thrown exceptions by redelivering the initial message" in {
        service.awaitEndpointActivation(1) {
          actorOf[RedeliveringConsumer].start
        } must be (true)
        mandatoryTemplate.requestBody("direct:redelivery-test", "hello") must equal ("accepted: hello")

      }
    }
  }
}

object ConsumerScalaTest {
  trait BlockingConsumer extends Consumer { self: Actor =>
    override def blocking = true
  }

  class TestConsumer(uri: String) extends Actor with Consumer {
    def endpointUri = uri
    protected def receive = {
      case msg: Message => self.reply("received %s" format msg.body)
    }
  }

  class TestBlocker(uri: String) extends Actor with BlockingConsumer {
    self.timeout = 1000
    def endpointUri = uri
    protected def receive = {
      case msg: Message => { /* do not reply */ }
    }
  }

  class ErrorHandlingConsumer extends Actor with BlockingConsumer {
    def endpointUri = "direct:error-handler-test"

    onRouteDefinition {rd: RouteDefinition =>
      rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
    }

    protected def receive = {
      case msg: Message => throw new Exception("error: %s" format msg.body)
    }
  }

  class RedeliveringConsumer extends Actor with BlockingConsumer {
    def endpointUri = "direct:redelivery-test"

    onRouteDefinition {rd: RouteDefinition =>
      rd.onException(classOf[Exception]).maximumRedeliveries(1).end
    }

    //
    // first message to this actor is not valid and will be rejected
    //

    var valid = false

    protected def receive = {
      case msg: Message => try {
        respondTo(msg)
      } finally {
        valid = true
      }
    }

    private def respondTo(msg: Message) =
      if (valid) self.reply("accepted: %s" format msg.body)
      else throw new Exception("rejected: %s" format msg.body)

  }

  trait TestTypedConsumer {
    @consume("direct:publish-test-3")
    def foo(s: String): String
    def bar(s: String): String
  }

  class TestTypedConsumerImpl extends TypedActor with TestTypedConsumer {
    def foo(s: String) = "foo: %s" format s
    @consume("direct:publish-test-4")
    def bar(s: String) = "bar: %s" format s
  }



}
