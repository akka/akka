package se.scalablesolutions.akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.junit.{Before, After, Test}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{Actor, ActorRef, ActorRegistry, ActorRegistered, ActorUnregistered}
import se.scalablesolutions.akka.camel.support.{SetExpectedMessageCount => SetExpectedTestMessageCount, _}

class PublishRequestorTest extends JUnitSuite {
  import PublishRequestorTest._

  var publisher: ActorRef = _
  var requestor: ActorRef = _
  var consumer: ActorRef = _

  @Before def setUp = {
    publisher = actorOf[PublisherMock].start
    requestor = actorOf[PublishRequestor].start
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
    val latch = publisher.!![CountDownLatch](SetExpectedTestMessageCount(1)).get
    requestor ! ActorRegistered(consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert((publisher !! GetRetainedMessage) ===
      Some(ConsumerRegistered(consumer.actor.getClass.getName, "mock:test", consumer.uuid, true)))
  }

  @Test def shouldReceiveConsumerUnregisteredEvent = {
    val latch = publisher.!![CountDownLatch](SetExpectedTestMessageCount(1)).get
    requestor ! ActorUnregistered(consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert((publisher !! GetRetainedMessage) ===
      Some(ConsumerUnregistered(consumer.actor.getClass.getName, "mock:test", consumer.uuid, true)))
  }

  // TODO: test active object method registration
  
}

object PublishRequestorTest {
  class PublisherMock extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }
}

