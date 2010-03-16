package se.scalablesolutions.akka.camel.service

import org.apache.camel.builder.RouteBuilder
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.annotation.consume
import se.scalablesolutions.akka.camel.{CamelContextManager, Consumer, Message}
import org.junit.{Ignore, Before, After, Test}

class CamelServiceTest extends JUnitSuite with CamelService {

  //
  // TODO: extend/rewrite unit tests
  // These tests currently only ensure proper functioning of basic features.
  //

  import CamelContextManager._

  var actor1: Actor = _
  var actor2: Actor = _
  var actor3: Actor = _

  @Before def setUp = {
    // register actors before starting the CamelService
    actor1 = new TestActor1().start
    actor2 = new TestActor2().start
    actor3 = new TestActor3().start
    // initialize global CamelContext
    init
    // customize global CamelContext
    context.addRoutes(new TestRouteBuilder)
    consumerPublisher.expectPublishCount(2)
    load
    consumerPublisher.awaitPublish
  }

  @After def tearDown = {
    unload
    actor1.stop
    actor2.stop
    actor3.stop
  }

  @Test def shouldReceiveResponseViaPreStartGeneratedRoutes = {
    assertEquals("Hello Martin (actor1)", template.requestBody("direct:actor1", "Martin"))
    assertEquals("Hello Martin (actor2)", template.requestBody("direct:actor2", "Martin"))
  }

  @Test def shouldReceiveResponseViaPostStartGeneratedRoute = {
    consumerPublisher.expectPublishCount(1)
    // register actor after starting CamelService
    val actor4 = new TestActor4().start
    consumerPublisher.awaitPublish
    assertEquals("Hello Martin (actor4)", template.requestBody("direct:actor4", "Martin"))
    actor4.stop
  }

  @Test def shouldReceiveResponseViaCustomRoute = {
    assertEquals("Hello Tester (actor3)", template.requestBody("direct:actor3", "Martin"))
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

class TestActor4 extends Actor with Consumer {
  def endpointUri = "direct:actor4"

  protected def receive = {
    case msg: Message => reply("Hello %s (actor4)" format msg.body)
  }
}

class TestRouteBuilder extends RouteBuilder {
  def configure {
    val actorUri = "actor:%s" format classOf[TestActor3].getName
    from("direct:actor3").transform(constant("Tester")).to("actor:actor3")
  }
}

