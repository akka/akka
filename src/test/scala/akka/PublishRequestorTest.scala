package akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.junit.{Before, After, Test}
import org.scalatest.junit.JUnitSuite

import akka.actor._
import akka.actor.Actor._
import akka.camel.support.{SetExpectedMessageCount => SetExpectedTestMessageCount, _}

class PublishRequestorTest extends JUnitSuite {
  import PublishRequestorTest._

  var publisher: ActorRef = _
  var requestor: ActorRef = _
  var consumer: ActorRef = _

  val ascendingMethodName = (r1: ConsumerMethodRegistered, r2: ConsumerMethodRegistered) =>
    r1.method.getName < r2.method.getName

  @Before def setUp: Unit = {
    publisher = actorOf[PublisherMock].start
    requestor = actorOf[PublishRequestor].start
    requestor ! PublishRequestorInit(publisher)
    consumer = actorOf(new Actor with Consumer {
      def endpointUri = "mock:test"
      protected def receive = null
    }).start
  }

  @After def tearDown = {
    AspectInitRegistry.removeListener(requestor);
    Actor.registry.shutdownAll
  }

  @Test def shouldReceiveOneConsumerMethodRegisteredEvent = {
    AspectInitRegistry.addListener(requestor)
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    val obj = TypedActor.newInstance(classOf[SampleTypedSingleConsumer], classOf[SampleTypedSingleConsumerImpl])
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val event = (publisher !! GetRetainedMessage).as[ConsumerMethodRegistered].get
    assert(event.endpointUri === "direct:foo")
    assert(event.typedActor === obj)
    assert(event.methodName === "foo")
  }

  @Test def shouldReceiveOneConsumerMethodUnregisteredEvent = {
    val obj = TypedActor.newInstance(classOf[SampleTypedSingleConsumer], classOf[SampleTypedSingleConsumerImpl])
    val latch = (publisher !! SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    AspectInitRegistry.addListener(requestor)
    TypedActor.stop(obj)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val event = (publisher !! GetRetainedMessage).as[ConsumerMethodUnregistered].get
    assert(event.endpointUri === "direct:foo")
    assert(event.typedActor === obj)
    assert(event.methodName === "foo")
  }

  @Test def shouldReceiveThreeConsumerMethodRegisteredEvents = {
    AspectInitRegistry.addListener(requestor)
    val latch = (publisher !! SetExpectedTestMessageCount(3)).as[CountDownLatch].get
    val obj = TypedActor.newInstance(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl])
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val request = GetRetainedMessages(_.isInstanceOf[ConsumerMethodRegistered])
    val events = (publisher !! request).as[List[ConsumerMethodRegistered]].get
    assert(events.map(_.method.getName).sortWith(_ < _) === List("m2", "m3", "m4"))
  }

  @Test def shouldReceiveThreeConsumerMethodUnregisteredEvents = {
    val obj = TypedActor.newInstance(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl])
    val latch = (publisher !! SetExpectedTestMessageCount(3)).as[CountDownLatch].get
    AspectInitRegistry.addListener(requestor)
    TypedActor.stop(obj)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val request = GetRetainedMessages(_.isInstanceOf[ConsumerMethodUnregistered])
    val events = (publisher !! request).as[List[ConsumerMethodUnregistered]].get
    assert(events.map(_.method.getName).sortWith(_ < _) === List("m2", "m3", "m4"))
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

object PublishRequestorTest {
  class PublisherMock extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }
}

