package se.scalablesolutions.akka.camel.service

import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.annotation.consume
import se.scalablesolutions.akka.camel.Consumer

object PublishTest {
  @consume("mock:test1")
  class ConsumeAnnotatedActor extends Actor {
    id = "test"
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

class PublishTest extends JUnitSuite {
  import PublishTest._
  
  @Test def shouldCreatePublishRequestList = {
    val publish = Publish.forConsumers(List(actorOf[ConsumeAnnotatedActor]))
    assert(publish === List(Publish("mock:test1", "test", false)))
  }

  @Test def shouldCreateSomePublishRequestWithActorId = {
    val publish = Publish.forConsumer(actorOf[ConsumeAnnotatedActor])
    assert(publish === Some(Publish("mock:test1", "test", false)))
  }

  @Test def shouldCreateSomePublishRequestWithActorUuid = {
    val ca = actorOf[ConsumerActor]
    val publish = Publish.forConsumer(ca)
    assert(publish === Some(Publish("mock:test2", ca.uuid, true)))
  }

  @Test def shouldCreateNone = {
    val publish = Publish.forConsumer(actorOf[PlainActor])
    assert(publish === None)
  }
}
