package se.scalablesolutions.akka.camel.component

import org.apache.camel.RuntimeCamelException
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.ActorRegistry
import se.scalablesolutions.akka.camel.support.{Respond, Countdown, Tester, Retain}
import se.scalablesolutions.akka.camel.{Message, CamelContextManager}

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
      val actor = new Tester with Retain with Countdown[Message]
      actor.start
      template.sendBody("actor:%s" format actor.getId, "Martin")
      assert(actor.waitFor)
      assert(actor.body === "Martin")
    }

    scenario("one-way communication using actor uuid") {
      val actor = new Tester with Retain with Countdown[Message]
      actor.start
      template.sendBody("actor:uuid:%s" format actor.uuid, "Martin")
      assert(actor.waitFor)
      assert(actor.body === "Martin")
    }

    scenario("two-way communication using actor id") {
      val actor = new Tester with Respond
      actor.start
      assert(template.requestBody("actor:%s" format actor.getId, "Martin") === "Hello Martin")
    }

    scenario("two-way communication using actor uuid") {
      val actor = new Tester with Respond
      actor.start
      assert(template.requestBody("actor:uuid:%s" format actor.uuid, "Martin") === "Hello Martin")
    }

    scenario("two-way communication with timeout") {
      val actor = new Tester {
        timeout = 1
      }
      actor.start
      intercept[RuntimeCamelException] {
        template.requestBody("actor:uuid:%s" format actor.uuid, "Martin")
      }
    }
  }
}