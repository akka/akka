package akka.camel

import org.apache.camel.CamelExecutionException

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor._
import akka.actor._
import akka.actor.TypedActor.Configuration._

/**
 * @author Martin Krasser
 */
class TypedConsumerScalaTest extends WordSpec with BeforeAndAfterAll with MustMatchers {
  import CamelContextManager.mandatoryTemplate
  import TypedConsumerScalaTest._

  var service: CamelService = _

  override protected def beforeAll = {
    registry.local.shutdownAll
    service = CamelServiceManager.startCamelService
  }

  override protected def afterAll = {
    service.stop
    registry.local.shutdownAll
  }

  "A responding, typed consumer" when {
    var actor: SampleTypedConsumer = null
    "started" must {
      "support in-out message exchanges via its endpoints" in {
        service.awaitEndpointActivation(3) {
          actor = TypedActor.typedActorOf(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl], defaultConfiguration)
        } must be(true)
        mandatoryTemplate.requestBodyAndHeader("direct:m2", "x", "test", "y") must equal("m2: x y")
        mandatoryTemplate.requestBodyAndHeader("direct:m3", "x", "test", "y") must equal("m3: x y")
        mandatoryTemplate.requestBodyAndHeader("direct:m4", "x", "test", "y") must equal("m4: x y")
      }
    }
    "stopped" must {
      "not support in-out message exchanges via its endpoints" in {
        service.awaitEndpointDeactivation(3) {
          TypedActor.stop(actor)
        } must be(true)
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
          actor = TypedActor.typedActorOf(classOf[TestTypedConsumer], classOf[TestTypedConsumerImpl], defaultConfiguration)
        } must be(true)
        mandatoryTemplate.requestBody("direct:publish-test-3", "x") must equal("foo: x")
        mandatoryTemplate.requestBody("direct:publish-test-4", "x") must equal("bar: x")
      }
    }
    "stopped" must {
      "not support in-out message exchanges via its endpoints" in {
        service.awaitEndpointDeactivation(2) {
          TypedActor.stop(actor)
        } must be(true)
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBody("direct:publish-test-3", "x")
        }
        intercept[CamelExecutionException] {
          mandatoryTemplate.requestBody("direct:publish-test-4", "x")
        }
      }
    }
  }
}

object TypedConsumerScalaTest {
  trait TestTypedConsumer {
    @consume("direct:publish-test-3")
    def foo(s: String): String
    def bar(s: String): String
  }

  class TestTypedConsumerImpl extends TestTypedConsumer {
    def foo(s: String) = "foo: %s" format s
    @consume("direct:publish-test-4")
    def bar(s: String) = "bar: %s" format s
  }
}
