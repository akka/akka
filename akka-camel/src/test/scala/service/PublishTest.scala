package se.scalablesolutions.akka.camel.service

import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.annotation.consume
import se.scalablesolutions.akka.camel.Consumer

class PublishTest extends JUnitSuite {

  @Test def shouldCreatePublishRequestList = {
    val publish = Publish.forConsumers(List(new ConsumeAnnotatedActor))
    assert(publish === List(Publish("mock:test1", "test", false)))
  }

  @Test def shouldCreateSomePublishRequestWithActorId = {
    val publish = Publish.forConsumer(new ConsumeAnnotatedActor)
    assert(publish === Some(Publish("mock:test1", "test", false)))
  }

  @Test def shouldCreateSomePublishRequestWithActorUuid = {
    val actor = new ConsumerActor
    val publish = Publish.forConsumer(actor)
    assert(publish === Some(Publish("mock:test2", actor.uuid, true)))
    assert(publish === Some(Publish("mock:test2", actor.uuid, true)))
  }

  @Test def shouldCreateNone = {
    val publish = Publish.forConsumer(new PlainActor)
    assert(publish === None)
  }

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