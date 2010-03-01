package se.scalablesolutions.akka.camel.service

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.junit.Assert._
import org.junit.{Before, After, Test}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.annotation.consume
import se.scalablesolutions.akka.camel.{Message, Consumer}

/**
 * @author Martin Krasser
 */
class CamelServiceTest extends JUnitSuite {

  import CamelContextManager.context

  context = new DefaultCamelContext
  context.addRoutes(new TestBuilder)

  val template = context.createProducerTemplate
  var service: CamelService = _
  var actor1: Actor = _
  var actor2: Actor = _
  var actor3: Actor = _

  @Before def setUp = {
    service = new CamelService {
      override def onUnload = super.onUnload
      override def onLoad = super.onLoad
    }

    actor1 = new TestActor1().start
    actor2 = new TestActor2().start
    actor3 = new TestActor3().start

    service.onLoad
    template.start

  }

  @After def tearDown = {
    actor1.stop
    actor2.stop
    actor3.stop

    template.stop
    service.onUnload
  }

  @Test def shouldReceiveResponseFromActor1ViaGeneratedRoute = {
    val result = template.requestBody("direct:actor1", "Martin")
    assertEquals("Hello Martin (actor1)", result)
  }

  @Test def shouldReceiveResponseFromActor2ViaGeneratedRoute = {
    val result = template.requestBody("direct:actor2", "Martin")
    assertEquals("Hello Martin (actor2)", result)
  }

  @Test def shouldReceiveResponseFromActor3ViaCustomRoute = {
    val result = template.requestBody("direct:actor3", "Martin")
    assertEquals("Hello Tester (actor3)", result)
  }

}

class TestActor1 extends Actor with Consumer {
  def endpointUri = "direct:actor1"

  protected def receive = {
    case msg: Message => reply("Hello %s (actor1)" format msg.body)
  }

}

@consume("direct:actor2")
class TestActor2 extends Actor {
  protected def receive = {
    case msg: Message => reply("Hello %s (actor2)" format msg.body)
  }
}

class TestActor3 extends Actor {
  id = "actor3"

  protected def receive = {
    case msg: Message => reply("Hello %s (actor3)" format msg.body)
  }
}

class TestBuilder extends RouteBuilder {
  def configure {
    val actorUri = "actor:%s" format classOf[TestActor3].getName
    from("direct:actor3").transform(constant("Tester")).to("actor:actor3")
  }
}

