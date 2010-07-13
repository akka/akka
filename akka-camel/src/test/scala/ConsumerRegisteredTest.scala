package se.scalablesolutions.akka.camel

import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.Actor._

object ConsumerRegisteredTest {
  class ConsumerActor1 extends Actor with Consumer {
    def endpointUri = "mock:test1"
    protected def receive = null
  }

  class ConsumerActor2 extends Actor with Consumer {
    def endpointUri = "mock:test2"
    override def blocking = true
    protected def receive = null
  }

  class PlainActor extends Actor {
    protected def receive = null
  }
}

class ConsumerRegisteredTest extends JUnitSuite {
  import ConsumerRegisteredTest._

  @Test def shouldCreateSomeNonBlockingPublishRequest = {
    val ca = actorOf[ConsumerActor1]
    val event = ConsumerRegistered.forConsumer(ca)
    assert(event === Some(ConsumerRegistered(ca, "mock:test1", ca.uuid, false)))
  }

  @Test def shouldCreateSomeBlockingPublishRequest = {
    val ca = actorOf[ConsumerActor2]
    val event = ConsumerRegistered.forConsumer(ca)
    assert(event === Some(ConsumerRegistered(ca, "mock:test2", ca.uuid, true)))
  }

  @Test def shouldCreateNone = {
    val event = ConsumerRegistered.forConsumer(actorOf[PlainActor])
    assert(event === None)
  }
}
