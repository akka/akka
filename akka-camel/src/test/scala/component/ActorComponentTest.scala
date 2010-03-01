package se.scalablesolutions.akka.camel.component

import org.apache.camel.Message
import org.apache.camel.impl.{SimpleRegistry, DefaultCamelContext}
import org.junit._
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite
import se.scalablesolutions.akka.actor.Actor

/**
 * @author Martin Krasser
 */
class ActorComponentTest extends JUnitSuite {

  val context = new DefaultCamelContext(new SimpleRegistry)
  val template = context.createProducerTemplate

  @Before def setUp = {
    context.start
    template.start
  }

  @After def tearDown = {
    template.stop
    context.stop
  }

  @Test def shouldCommunicateWithActorReferencedById = {
    val actor = new ActorComponentTestActor
    actor.start
    assertEquals("Hello Martin", template.requestBody("actor:%s" format actor.getId, "Martin"))
    assertEquals("Hello Martin", template.requestBody("actor:id:%s" format actor.getId, "Martin"))
    actor.stop
  }

  @Test def shouldCommunicateWithActorReferencedByUuid = {
    val actor = new ActorComponentTestActor
    actor.start
    assertEquals("Hello Martin", template.requestBody("actor:uuid:%s" format actor.uuid, "Martin"))
    actor.stop
  }

}

class ActorComponentTestActor extends Actor {
  protected def receive = {
    case msg: Message => reply("Hello %s" format msg.getBody)
  }
}