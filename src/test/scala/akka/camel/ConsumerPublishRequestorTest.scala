package akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.junit.{Before, After, Test}
import org.scalatest.junit.JUnitSuite

import akka.actor._
import akka.actor.Actor._
import akka.camel.CamelTestSupport.{SetExpectedMessageCount => SetExpectedTestMessageCount, _}

class ConsumerPublishRequestorTest extends JUnitSuite {
  import ConsumerPublishRequestorTest._

  var publisher: ActorRef = _
  var requestor: ActorRef = _
  var consumer: ActorRef = _

  @Before def setUp: Unit = {
    publisher = actorOf(new ConsumerPublisherMock).start
    requestor = actorOf(new ConsumerPublishRequestor).start
    requestor ! InitPublishRequestor(publisher)
    consumer = actorOf(new Actor with Consumer {
      def endpointUri = "mock:test"
      protected def receive = null
    }).start
  }

  @After def tearDown = {
    Actor.registry.removeListener(requestor);
    Actor.registry.shutdownAll
  }

  @Test def shouldReceiveOneConsumerRegisteredEvent = {
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    requestor ! ActorRegistered(consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert((publisher !! GetRetainedMessage) ===
      Some(ConsumerActorRegistered(consumer, consumer.actor.asInstanceOf[Consumer])))
  }

  @Test def shouldReceiveOneConsumerUnregisteredEvent = {
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    requestor ! ActorUnregistered(consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert((publisher !! GetRetainedMessage) ===
      Some(ConsumerActorUnregistered(consumer, consumer.actor.asInstanceOf[Consumer])))
  }
}

object ConsumerPublishRequestorTest {
  class ConsumerPublisherMock extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }
}

