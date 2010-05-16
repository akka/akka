package se.scalablesolutions.akka.camel.component

import org.apache.camel.RuntimeCamelException
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.{ActorRegistry, Actor}
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
    import Actor._
    
    scenario("one-way communication using actor id") {
      val actor = actorOf(new Tester with Retain with Countdown[Message])
      actor.start
      template.sendBody("actor:%s" format actor.id, "Martin")
      assert(actor.actor.asInstanceOf[Countdown[Message]].waitFor)
      assert(actor.actor.asInstanceOf[Retain].body === "Martin")
    }

    scenario("one-way communication using actor uuid") {
      val actor = actorOf(new Tester with Retain with Countdown[Message])
      actor.start
      template.sendBody("actor:uuid:%s" format actor.uuid, "Martin")
      assert(actor.actor.asInstanceOf[Countdown[Message]].waitFor)
      assert(actor.actor.asInstanceOf[Retain].body === "Martin")
    }

    scenario("two-way communication using actor id") {
      val actor = actorOf(new Tester with Respond)
      actor.start
      assert(template.requestBody("actor:%s" format actor.id, "Martin") === "Hello Martin")
    }

    scenario("two-way communication using actor uuid") {
      val actor = actorOf(new Tester with Respond)
      actor.start
      assert(template.requestBody("actor:uuid:%s" format actor.uuid, "Martin") === "Hello Martin")
    }

    scenario("two-way communication with timeout") {
      val actor = actorOf(new Tester {
        self.timeout = 1
      })
      actor.start
      intercept[RuntimeCamelException] {
        template.requestBody("actor:uuid:%s" format actor.uuid, "Martin")
      }
    }
  }
}