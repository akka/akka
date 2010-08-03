package se.scalablesolutions.akka.camel.component

import java.util.concurrent.{TimeUnit, CountDownLatch}

import org.apache.camel.RuntimeCamelException
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActorRegistry, Actor}
import se.scalablesolutions.akka.camel.{Failure, Message, CamelContextManager}
import se.scalablesolutions.akka.camel.support._

class ActorComponentFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  import ActorComponentFeatureTest._

  override protected def beforeAll = {
    ActorRegistry.shutdownAll
    CamelContextManager.init
    CamelContextManager.context.addRoutes(new TestRoute)
    CamelContextManager.start
  }

  override protected def afterAll = CamelContextManager.stop

  override protected def afterEach = {
    ActorRegistry.shutdownAll
    mockEndpoint.reset
  }

  feature("Communicate with an actor from a Camel application using actor endpoint URIs") {
    import CamelContextManager.template

    scenario("one-way communication using actor id") {
      val actor = actorOf[Tester1].start
      val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
      template.sendBody("actor:%s" format actor.id, "Martin")
      assert(latch.await(5000, TimeUnit.MILLISECONDS))
      val reply = (actor !! GetRetainedMessage).get.asInstanceOf[Message]
      assert(reply.body === "Martin")
    }

    scenario("one-way communication using actor uuid") {
      val actor = actorOf[Tester1].start
      val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
      template.sendBody("actor:uuid:%s" format actor.uuid, "Martin")
      assert(latch.await(5000, TimeUnit.MILLISECONDS))
      val reply = (actor !! GetRetainedMessage).get.asInstanceOf[Message]
      assert(reply.body === "Martin")
    }

    scenario("two-way communication using actor id") {
      val actor = actorOf[Tester2].start
      assert(template.requestBody("actor:%s" format actor.id, "Martin") === "Hello Martin")
    }

    scenario("two-way communication using actor uuid") {
      val actor = actorOf[Tester2].start
      assert(template.requestBody("actor:uuid:%s" format actor.uuid, "Martin") === "Hello Martin")
    }

    scenario("two-way communication with timeout") {
      val actor = actorOf[Tester3].start
      intercept[RuntimeCamelException] {
        template.requestBody("actor:uuid:%s?blocking=true" format actor.uuid, "Martin")
      }
    }

    scenario("two-way async communication with failure response") {
      mockEndpoint.expectedBodiesReceived("whatever")
      template.requestBody("direct:failure-test-1", "whatever")
      mockEndpoint.assertIsSatisfied
    }

    scenario("two-way sync communication with exception") {
      mockEndpoint.expectedBodiesReceived("whatever")
      template.requestBody("direct:failure-test-2", "whatever")
      mockEndpoint.assertIsSatisfied
    }
  }

  private def mockEndpoint = CamelContextManager.context.getEndpoint("mock:mock", classOf[MockEndpoint])
}

object ActorComponentFeatureTest {
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
      from("direct:failure-test-1")
        .onException(classOf[Exception]).to("mock:mock").handled(true).end
        .to("actor:uuid:%s" format failWithMessage.uuid)
      from("direct:failure-test-2")
        .onException(classOf[Exception]).to("mock:mock").handled(true).end
        .to("actor:uuid:%s?blocking=true" format failWithException.uuid)
    }
  }
}
