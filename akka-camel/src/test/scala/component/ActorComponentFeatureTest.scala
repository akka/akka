package se.scalablesolutions.akka.camel.component

import java.util.concurrent.{TimeUnit, CountDownLatch}

import org.apache.camel.RuntimeCamelException
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActorRegistry, Actor}
import se.scalablesolutions.akka.camel.{Message, CamelContextManager}
import se.scalablesolutions.akka.camel.support._

class ActorComponentFeatureTest extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  override protected def beforeAll = {
    ActorRegistry.shutdownAll
    CamelContextManager.init
    CamelContextManager.start
  }

  override protected def afterAll = CamelContextManager.stop

  override protected def afterEach = ActorRegistry.shutdownAll

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
  }
}
