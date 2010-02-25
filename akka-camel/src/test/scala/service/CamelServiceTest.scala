package se.scalablesolutions.akka.camel.service

import org.apache.camel.Message
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.junit.Test
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.annotation.consume
import se.scalablesolutions.akka.camel.CamelConsumer


/**
 * @author Martin Krasser
 */
class CamelServiceTest extends JUnitSuite {

  import CamelServiceTestSetup._

  @Test
  def testActor1() {
    val result = template.requestBody("direct:actor1", "Martin")
    assertEquals("Hello Martin (actor1)", result)
  }

  @Test
  def testActor2() {
    val result = template.requestBody("direct:actor2", "Martin")
    assertEquals("Hello Martin (actor2)", result)
  }

  @Test
  def testActor3() {
    val result = template.requestBody("direct:actor3", "Martin")
    assertEquals("Hello Tester (actor3)", result)
  }

}

class TestActor1 extends Actor with CamelConsumer {

  def endpointUri = "direct:actor1"

  protected def receive = {
    case msg: Message => reply("Hello %s (actor1)" format msg.getBody)
  }

}

@consume("direct:actor2")
class TestActor2 extends Actor {

  protected def receive = {
    case msg: Message => reply("Hello %s (actor2)" format msg.getBody)
  }

}

class TestActor3 extends Actor {

  protected def receive = {
    case msg: Message => reply("Hello %s (actor3)" format msg.getBody)
  }

}

class TestBuilder extends RouteBuilder {

  def configure {
    val actorUri = "actor://%s" format classOf[TestActor3].getName
    from("direct:actor3").transform(constant("Tester")).to(actorUri)
  }

}

object CamelServiceTestSetup extends CamelService {

  import CamelContextManager.context

  // use a custom camel context
  context = new DefaultCamelContext

  val template = context.createProducerTemplate
  var loaded = false

  onLoad

  override def onLoad = {
    if (!loaded) {
      // use a custom camel context
      context.addRoutes(new TestBuilder)
      // register test actors
      new TestActor1().start
      new TestActor2().start
      new TestActor3().start
      // start Camel service
      super.onLoad

      template.start
      loaded = true
    }
  }

}

