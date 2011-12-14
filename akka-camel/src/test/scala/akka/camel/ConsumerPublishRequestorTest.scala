package akka.camel

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import org.junit.{ Before, After, Test }
import org.scalatest.junit.JUnitSuite

import akka.actor._
import akka.actor.Actor._
import akka.camel.CamelTestSupport.{ SetExpectedMessageCount ⇒ SetExpectedTestMessageCount, _ }
import akka.dispatch.Await

class ConsumerPublishRequestorTest extends JUnitSuite {
  import ConsumerPublishRequestorTest._

  var publisher: ActorRef = _
  var requestor: ActorRef = _
  var consumer: LocalActorRef = _

  @Before
  def setUp{
    publisher = actorOf(Props(new ConsumerPublisherMock)
    requestor = actorOf(Props(new ConsumerPublishRequestor)
    requestor ! InitPublishRequestor(publisher)
    consumer = actorOf(Props(new Actor with Consumer {
      def endpointUri = "mock:test"
      protected def receive = null
    }).asInstanceOf[LocalActorRef]
  }

  @After
  def tearDown = {
    Actor.registry.removeListener(requestor);
    Actor.registry.local.shutdownAll
  }

  @Test
  def shouldReceiveOneConsumerRegisteredEvent = {
    val latch = Await.result((publisher ? SetExpectedTestMessageCount(1)).mapTo[CountDownLatch], 5 seconds)
    requestor ! ActorRegistered(consumer.address, consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert(Await.result(publisher ? GetRetainedMessage, 5 seconds) ===
      ConsumerActorRegistered(consumer, consumer.underlyingActorInstance.asInstanceOf[Consumer]))
  }

  @Test
  def shouldReceiveOneConsumerUnregisteredEvent = {
    val latch = Await.result((publisher ? SetExpectedTestMessageCount(1)).mapTo[CountDownLatch], 5 seconds)
    requestor ! ActorUnregistered(consumer.address, consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert(Await.result(publisher ? GetRetainedMessage, 5 seconds) ===
      ConsumerActorUnregistered(consumer, consumer.underlyingActorInstance.asInstanceOf[Consumer]))
  }
}

object ConsumerPublishRequestorTest {
  class ConsumerPublisherMock extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }
}

