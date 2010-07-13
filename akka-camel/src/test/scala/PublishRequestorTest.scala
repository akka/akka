package se.scalablesolutions.akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.junit.{Before, After, Test}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.Actor._
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

  @Test def shouldReceiveConsumerMethodRegisteredEvent = {
    val obj = ActiveObject.newInstance(classOf[PojoSingle])
    val init = AspectInit(classOf[PojoSingle], null, None, 1000)
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    requestor ! AspectInitRegistered(obj, init)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val event = (publisher !! GetRetainedMessage).get.asInstanceOf[ConsumerMethodRegistered]
    assert(event.init === init)
    assert(event.uri === "direct:foo")
    assert(event.activeObject === obj)
    assert(event.method.getName === "foo")
  }

  @Test def shouldReceiveConsumerMethodUnregisteredEvent = {
    val obj = ActiveObject.newInstance(classOf[PojoSingle])
    val init = AspectInit(classOf[PojoSingle], null, None, 1000)
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    requestor ! AspectInitUnregistered(obj, init)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val event = (publisher !! GetRetainedMessage).get.asInstanceOf[ConsumerMethodUnregistered]
    assert(event.init === init)
    assert(event.uri === "direct:foo")
    assert(event.activeObject === obj)
    assert(event.method.getName === "foo")
  }

  @Test def shouldReceiveConsumerRegisteredEvent = {
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    requestor ! ActorRegistered(consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert((publisher !! GetRetainedMessage) ===
      Some(ConsumerRegistered(consumer, "mock:test", consumer.uuid, false)))
  }

  @Test def shouldReceiveConsumerUnregisteredEvent = {
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    requestor ! ActorUnregistered(consumer)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    assert((publisher !! GetRetainedMessage) ===
      Some(ConsumerUnregistered(consumer, "mock:test", consumer.uuid)))
  }
}

object PublishRequestorTest {
  class PublisherMock extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }
}

