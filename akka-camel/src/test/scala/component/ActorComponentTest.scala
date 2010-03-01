package se.scalablesolutions.akka.camel.component

import org.junit._
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite
import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.camel.Message
import org.apache.camel.{CamelContext, ExchangePattern}
import org.apache.camel.impl.{DefaultExchange, SimpleRegistry, DefaultCamelContext}

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


