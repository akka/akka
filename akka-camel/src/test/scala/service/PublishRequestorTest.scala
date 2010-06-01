package se.scalablesolutions.akka.camel.service

import _root_.org.junit.{Before, After, Test}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.{Actor, ActorRef, ActorRegistry, ActorRegistered, ActorUnregistered}
import se.scalablesolutions.akka.actor.Actor._

import se.scalablesolutions.akka.camel.Consumer
import se.scalablesolutions.akka.camel.support.{Receive, Countdown}

object PublishRequestorTest {
  class PublisherMock extends Actor with Receive[ConsumerEvent] {
    var received: ConsumerEvent = _
    protected def receive = {
      case msg: ConsumerRegistered => onMessage(msg)
      case msg: ConsumerUnregistered => onMessage(msg)
    }
    def onMessage(msg: ConsumerEvent) = received = msg
  }
}

class PublishRequestorTest extends JUnitSuite {
  import PublishRequestorTest._

  var publisher: ActorRef = _
  var requestor: ActorRef = _
  var consumer: ActorRef = _

  @Before def setUp = {
    publisher = actorOf(new PublisherMock with Countdown[ConsumerEvent]).start
    requestor = actorOf(new PublishRequestor).start
    requestor ! PublishRequestorInit(publisher)
    consumer = actorOf(new Actor with Consumer {
      def endpointUri = "mock:test"
      protected def receive = null
    }).start

  }

  @After def tearDown = {
    ActorRegistry.shutdownAll
  }

  @Test def shouldReceiveConsumerRegisteredEvent = {
    requestor.!(ActorRegistered(consumer))(None)
    publisher.actor.asInstanceOf[Countdown[ConsumerEvent]].waitFor
    assert(publisher.actor.asInstanceOf[PublisherMock].received ===
      ConsumerRegistered(consumer.actor.getClass.getName, "mock:test", consumer.uuid, true))
  }

  @Test def shouldReceiveConsumerUnregisteredEvent = {
    requestor.!(ActorUnregistered(consumer))(None)
    publisher.actor.asInstanceOf[Countdown[ConsumerRegistered]].waitFor
    assert(publisher.actor.asInstanceOf[PublisherMock].received ===
      ConsumerUnregistered(consumer.actor.getClass.getName, "mock:test", consumer.uuid, true))
  }
}
