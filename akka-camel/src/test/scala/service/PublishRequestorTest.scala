package se.scalablesolutions.akka.camel.service

import org.junit.{After, Test}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.camel.Consumer
import se.scalablesolutions.akka.camel.support.{Receive, Countdown}
import se.scalablesolutions.akka.actor.{ActorRegistry, ActorRegistered, Actor}
import Actor._

class PublishRequestorTest extends JUnitSuite {
  @After def tearDown = ActorRegistry.shutdownAll

  @Test def shouldReceivePublishRequestOnActorRegisteredEvent = {
    val consumer = newActor(() => new Actor with Consumer {
      def endpointUri = "mock:test"
      protected def receive = null
    })
    val publisher = newActor(() => new PublisherMock with Countdown[Publish])
    val requestor = newActor(() => new PublishRequestor(publisher))
    publisher.start
    requestor.start
    requestor.!(ActorRegistered(consumer))(None)
    publisher.actor.asInstanceOf[Countdown[Publish]].waitFor
    assert(publisher.actor.asInstanceOf[PublisherMock].received === Publish("mock:test", consumer.uuid, true))
    publisher.stop
    requestor.stop
  }

  class PublisherMock extends Actor with Receive[Publish] {
    var received: Publish = _
    protected def receive = {
      case msg: Publish => onMessage(msg)
    }
    def onMessage(msg: Publish) = received = msg
  }
}