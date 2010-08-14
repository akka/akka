package se.scalablesolutions.akka.camel

import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.{Actor, UntypedActor}

class ConsumerRegisteredTest extends JUnitSuite {
  import ConsumerRegisteredTest._

  @Test def shouldCreateSomeNonBlockingPublishRequestFromConsumer = {
    val c = Actor.actorOf[ConsumerActor1]
    val event = ConsumerRegistered.forConsumer(c)
    assert(event === Some(ConsumerRegistered(c, "mock:test1", c.uuid, false)))
  }

  @Test def shouldCreateSomeBlockingPublishRequestFromConsumer = {
    val c = Actor.actorOf[ConsumerActor2]
    val event = ConsumerRegistered.forConsumer(c)
    assert(event === Some(ConsumerRegistered(c, "mock:test2", c.uuid, true)))
  }

  @Test def shouldCreateNoneFromConsumer = {
    val event = ConsumerRegistered.forConsumer(Actor.actorOf[PlainActor])
    assert(event === None)
  }

  @Test def shouldCreateSomeNonBlockingPublishRequestFromUntypedConsumer = {
    val uc = UntypedActor.actorOf(classOf[SampleUntypedConsumer]).actorRef
    val event = ConsumerRegistered.forConsumer(uc)
    assert(event === Some(ConsumerRegistered(uc, "direct:test-untyped-consumer", uc.uuid, false)))
  }

  @Test def shouldCreateSomeBlockingPublishRequestFromUntypedConsumer = {
    val uc = UntypedActor.actorOf(classOf[SampleUntypedConsumerBlocking]).actorRef
    val event = ConsumerRegistered.forConsumer(uc)
    assert(event === Some(ConsumerRegistered(uc, "direct:test-untyped-consumer-blocking", uc.uuid, true)))
  }

  @Test def shouldCreateNoneFromUntypedConsumer = {
    val a = UntypedActor.actorOf(classOf[SampleUntypedActor]).actorRef
    val event = ConsumerRegistered.forConsumer(a)
    assert(event === None)
  }
}

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
