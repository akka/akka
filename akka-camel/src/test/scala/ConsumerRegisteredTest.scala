package se.scalablesolutions.akka.camel

import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.annotation.consume

object ConsumerRegisteredTest {
  @consume("mock:test1")
  class ConsumeAnnotatedActor extends Actor {
    self.id = "test"
    protected def receive = null
  }

  class ConsumerActor extends Actor with Consumer {
    def endpointUri = "mock:test2"
    protected def receive = null
  }

  class PlainActor extends Actor {
    protected def receive = null
  }
}

class ConsumerRegisteredTest extends JUnitSuite {
  import ConsumerRegisteredTest._

  @Test def shouldCreatePublishRequestList = {
    val as = List(actorOf[ConsumeAnnotatedActor])
    val events = for (a <- as; e <- ConsumerRegistered.forConsumer(a)) yield e
    assert(events === List(ConsumerRegistered(classOf[ConsumeAnnotatedActor].getName, "mock:test1", "test", false)))
  }

  @Test def shouldCreateSomePublishRequestWithActorId = {
    val event = ConsumerRegistered.forConsumer(actorOf[ConsumeAnnotatedActor])
    assert(event === Some(ConsumerRegistered(classOf[ConsumeAnnotatedActor].getName, "mock:test1", "test", false)))
  }

  @Test def shouldCreateSomePublishRequestWithActorUuid = {
    val ca = actorOf[ConsumerActor]
    val event = ConsumerRegistered.forConsumer(ca)
    assert(event === Some(ConsumerRegistered(ca.actor.getClass.getName, "mock:test2", ca.uuid, true)))
  }

  @Test def shouldCreateNone = {
    val event = ConsumerRegistered.forConsumer(actorOf[PlainActor])
    assert(event === None)
  }
}
