package se.scalablesolutions.akka.camel.component

import org.apache.camel.{Message, RuntimeCamelException}
import org.apache.camel.impl.{SimpleRegistry, DefaultCamelContext}
import org.junit._
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor

/**
 * @author Martin Krasser
 */
class ActorComponentTest extends JUnitSuite {

  import ActorComponentTestSetup._

  val actor = ActorComponentTestActor.start

  @Test
  def testMatchIdOnly() {
    val result = template.requestBody("actor:%s" format actor.id, "Martin")
    assertEquals("Hello Martin", result)
  }

  @Test
  def testMatchIdAndUuid() {
    val result = template.requestBody("actor:%s/%s" format (actor.id, actor.uuid), "Martin")
    assertEquals("Hello Martin", result)
  }

  @Test
  def testMatchIdButNotUuid() {
    intercept[RuntimeCamelException] {
      template.requestBody("actor:%s/%s" format (actor.id, "wrong"), "Martin")
    }
  }

}

object ActorComponentTestActor extends Actor {

  protected def receive = {
    case msg: Message => reply("Hello %s" format msg.getBody)
  }

}

object ActorComponentTestSetup {

  val context = new DefaultCamelContext(new SimpleRegistry)
  val template = context.createProducerTemplate

  context.start
  template.start

}