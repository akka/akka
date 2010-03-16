package se.scalablesolutions.akka.camel.component

import org.junit._
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.camel.{CamelContextLifecycle, Message}

class ActorComponentTest extends JUnitSuite with CamelContextLifecycle {

  //
  // TODO: extend/rewrite unit tests
  // These tests currently only ensure proper functioning of basic features.
  //

  @Before def setUp = {
    init
    start
  }

  @After def tearDown = {
    stop
  }

  @Test def shouldReceiveResponseFromActorReferencedById = {
    val actor = new TestActor
    actor.start
    assertEquals("Hello Martin", template.requestBody("actor:%s" format actor.getId, "Martin"))
    assertEquals("Hello Martin", template.requestBody("actor:id:%s" format actor.getId, "Martin"))
    actor.stop
  }

  @Test def shouldReceiveResponseFromActorReferencedByUuid = {
    val actor = new TestActor
    actor.start
    assertEquals("Hello Martin", template.requestBody("actor:uuid:%s" format actor.uuid, "Martin"))
    actor.stop
  }

  class TestActor extends Actor {
    protected def receive = {
      case msg: Message => reply("Hello %s" format msg.body)
    }
  }

}


