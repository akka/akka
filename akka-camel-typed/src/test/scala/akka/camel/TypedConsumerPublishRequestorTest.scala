package akka.camel

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import org.junit.{ Before, After, Test }
import org.scalatest.junit.JUnitSuite

import akka.actor._
import akka.actor.Actor._
import akka.camel.TypedCamelTestSupport.{ SetExpectedMessageCount ⇒ SetExpectedTestMessageCount, _ }

class TypedConsumerPublishRequestorTest extends JUnitSuite {
  import TypedConsumerPublishRequestorTest._

  var publisher: ActorRef = _
  var requestor: ActorRef = _
  var consumer: ActorRef = _

  val ascendingMethodName = (r1: ConsumerMethodRegistered, r2: ConsumerMethodRegistered) ⇒
    r1.method.getName < r2.method.getName

  @Before
  def setUp: Unit = {
    publisher = actorOf(new TypedConsumerPublisherMock)
    requestor = actorOf(new TypedConsumerPublishRequestor)
    requestor ! InitPublishRequestor(publisher)
    consumer = actorOf(new Actor with Consumer {
      def endpointUri = "mock:test"
      protected def receive = null
    })
  }

  @After
  def tearDown = {
    Actor.registry.removeListener(requestor);
    Actor.registry.local.shutdownAll
  }

  @Test
  def shouldReceiveOneConsumerMethodRegisteredEvent = {
    Actor.registry.addListener(requestor)
    val latch = (publisher ? SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    val obj = TypedActor.typedActorOf(classOf[SampleTypedSingleConsumer], classOf[SampleTypedSingleConsumerImpl], Props())
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val event = (publisher ? GetRetainedMessage).as[ConsumerMethodRegistered].get
    assert(event.endpointUri === "direct:foo")
    assert(event.typedActor === obj)
    assert(event.methodName === "foo")
  }

  @Test
  def shouldReceiveOneConsumerMethodUnregisteredEvent = {
    val obj = TypedActor.typedActorOf(classOf[SampleTypedSingleConsumer], classOf[SampleTypedSingleConsumerImpl], Props())
    val latch = (publisher ? SetExpectedTestMessageCount(1)).as[CountDownLatch].get
    Actor.registry.addListener(requestor)
    TypedActor.stop(obj)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val event = (publisher ? GetRetainedMessage).as[ConsumerMethodUnregistered].get
    assert(event.endpointUri === "direct:foo")
    assert(event.typedActor === obj)
    assert(event.methodName === "foo")
  }

  @Test
  def shouldReceiveThreeConsumerMethodRegisteredEvents = {
    Actor.registry.addListener(requestor)
    val latch = (publisher ? SetExpectedTestMessageCount(3)).as[CountDownLatch].get
    val obj = TypedActor.typedActorOf(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl], Props())
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val request = GetRetainedMessages(_.isInstanceOf[ConsumerMethodRegistered])
    val events = (publisher ? request).as[List[ConsumerMethodRegistered]].get
    assert(events.map(_.method.getName).sortWith(_ < _) === List("m2", "m3", "m4"))
  }

  @Test
  def shouldReceiveThreeConsumerMethodUnregisteredEvents = {
    val obj = TypedActor.typedActorOf(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl], Props())
    val latch = (publisher ? SetExpectedTestMessageCount(3)).as[CountDownLatch].get
    Actor.registry.addListener(requestor)
    TypedActor.stop(obj)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val request = GetRetainedMessages(_.isInstanceOf[ConsumerMethodUnregistered])
    val events = (publisher ? request).as[List[ConsumerMethodUnregistered]].get
    assert(events.map(_.method.getName).sortWith(_ < _) === List("m2", "m3", "m4"))
  }
}

object TypedConsumerPublishRequestorTest {
  class TypedConsumerPublisherMock extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }
}

