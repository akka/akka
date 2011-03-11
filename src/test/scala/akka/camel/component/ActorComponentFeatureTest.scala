package akka.camel.component

import java.util.concurrent.{TimeUnit, CountDownLatch}

import org.apache.camel.RuntimeCamelException
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import akka.actor.Actor
import akka.actor.Actor._
import akka.camel.{Failure, Message, CamelContextManager}
import akka.camel.CamelTestSupport._

class ActorComponentFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  import ActorComponentFeatureTest._

  override protected def beforeAll = {
    Actor.registry.shutdownAll
    CamelContextManager.init
    CamelContextManager.mandatoryContext.addRoutes(new TestRoute)
    CamelContextManager.start
  }

  override protected def afterAll = CamelContextManager.stop

  override protected def afterEach = {
    Actor.registry.shutdownAll
    mockEndpoint.reset
  }

  feature("Communicate with an actor via an actor:uuid endpoint") {
    import CamelContextManager.mandatoryTemplate

    scenario("one-way communication") {
      val actor = actorOf[Tester1].start
      val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
      mandatoryTemplate.sendBody("actor:uuid:%s" format actor.uuid, "Martin")
      assert(latch.await(5000, TimeUnit.MILLISECONDS))
      val reply = (actor !! GetRetainedMessage).get.asInstanceOf[Message]
      assert(reply.body === "Martin")
    }

    scenario("two-way communication") {
      val actor = actorOf[Tester2].start
      assert(mandatoryTemplate.requestBody("actor:uuid:%s" format actor.uuid, "Martin") === "Hello Martin")
    }

    scenario("two-way communication with timeout") {
      val actor = actorOf[Tester3].start
      intercept[RuntimeCamelException] {
        mandatoryTemplate.requestBody("actor:uuid:%s?blocking=true" format actor.uuid, "Martin")
      }
    }

    scenario("two-way communication via a custom route with failure response") {
      mockEndpoint.expectedBodiesReceived("whatever")
      mandatoryTemplate.requestBody("direct:failure-test-1", "whatever")
      mockEndpoint.assertIsSatisfied
    }

    scenario("two-way communication via a custom route with exception") {
      mockEndpoint.expectedBodiesReceived("whatever")
      mandatoryTemplate.requestBody("direct:failure-test-2", "whatever")
      mockEndpoint.assertIsSatisfied
    }
  }

  feature("Communicate with an actor via an actor:id endpoint") {
    import CamelContextManager.mandatoryTemplate

    scenario("one-way communication") {
      val actor = actorOf[Tester1].start
      val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
      mandatoryTemplate.sendBody("actor:%s" format actor.id, "Martin")
      assert(latch.await(5000, TimeUnit.MILLISECONDS))
      val reply = (actor !! GetRetainedMessage).get.asInstanceOf[Message]
      assert(reply.body === "Martin")
    }

    scenario("two-way communication") {
      val actor = actorOf[Tester2].start
      assert(mandatoryTemplate.requestBody("actor:%s" format actor.id, "Martin") === "Hello Martin")
    }

    scenario("two-way communication via a custom route") {
      val actor = actorOf[CustomIdActor].start
      assert(mandatoryTemplate.requestBody("direct:custom-id-test-1", "Martin") === "Received Martin")
      assert(mandatoryTemplate.requestBody("direct:custom-id-test-2", "Martin") === "Received Martin")
    }
  }

  private def mockEndpoint = CamelContextManager.mandatoryContext.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ActorComponentFeatureTest {
  class CustomIdActor extends Actor {
    self.id = "custom-id"
    protected def receive = {
      case msg: Message => self.reply("Received %s" format msg.body)
    }
  }

  class FailWithMessage extends Actor {
    protected def receive = {
      case msg: Message => self.reply(Failure(new Exception("test")))
    }
  }

  class FailWithException extends Actor {
    protected def receive = {
      case msg: Message => throw new Exception("test")
    }
  }

  class TestRoute extends RouteBuilder {
    val failWithMessage = actorOf[FailWithMessage].start
    val failWithException = actorOf[FailWithException].start
    def configure {
      from("direct:custom-id-test-1").to("actor:custom-id")
      from("direct:custom-id-test-2").to("actor:id:custom-id")
      from("direct:failure-test-1")
        .onException(classOf[Exception]).to("mock:mock").handled(true).end
        .to("actor:uuid:%s" format failWithMessage.uuid)
      from("direct:failure-test-2")
        .onException(classOf[Exception]).to("mock:mock").handled(true).end
        .to("actor:uuid:%s?blocking=true" format failWithException.uuid)
    }
  }
}
