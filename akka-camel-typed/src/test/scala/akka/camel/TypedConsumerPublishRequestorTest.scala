package akka.camel

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import org.junit.{ Before, After, Test }
import org.scalatest.junit.JUnitSuite
import scala.util.duration._
import akka.actor._
import akka.actor.Actor._
import akka.camel.TypedCamelTestSupport.{ SetExpectedMessageCount ⇒ SetExpectedTestMessageCount, _ }
import akka.dispatch.Await

class TypedConsumerPublishRequestorTest extends JUnitSuite {
  import TypedConsumerPublishRequestorTest._

  var publisher: ActorRef = _
  var requestor: ActorRef = _
  var consumer: ActorRef = _

  val ascendingMethodName = (r1: ConsumerMethodRegistered, r2: ConsumerMethodRegistered) ⇒
    r1.method.getName < r2.method.getName

  @Before
  def setUp{
    publisher = actorOf(Props(new TypedConsumerPublisherMock)
    requestor = actorOf(Props(new TypedConsumerPublishRequestor)
    requestor ! InitPublishRequestor(publisher)
    consumer = actorOf(Props(new Actor with Consumer {
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
    val latch = Await.result((publisher ? SetExpectedTestMessageCount(1)).mapTo[CountDownLatch], 3 seconds)
    val obj = TypedActor.typedActorOf(classOf[SampleTypedSingleConsumer], classOf[SampleTypedSingleConsumerImpl], Props())
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val event = Await.result((publisher ? GetRetainedMessage).mapTo[ConsumerMethodRegistered], 3 seconds)
    assert(event.endpointUri === "direct:foo")
    assert(event.typedActor === obj)
    assert(event.methodName === "foo")
  }

  @Test
  def shouldReceiveOneConsumerMethodUnregisteredEvent = {
    val latch = Await.result((publisher ? SetExpectedTestMessageCount(1)).mapTo[CountDownLatch], 3 seconds)
    Actor.registry.addListener(requestor)

    val obj = TypedActor.typedActorOf(classOf[SampleTypedSingleConsumer], classOf[SampleTypedSingleConsumerImpl], Props())

    assert(latch.await(5000, TimeUnit.MILLISECONDS))

    val ignorableEvent = Await.result((publisher ? GetRetainedMessage).mapTo[ConsumerMethodRegistered], 3 seconds)

    val latch2 = Await.result((publisher ? SetExpectedTestMessageCount(1)).mapTo[CountDownLatch], 3 seconds)
    TypedActor.stop(obj)

    assert(latch2.await(5000, TimeUnit.MILLISECONDS))

    val event = Await.result((publisher ? GetRetainedMessage).mapTo[ConsumerMethodUnregistered], 3 seconds)

    assert(event.endpointUri === "direct:foo")
    assert(event.typedActor === obj)
    assert(event.methodName === "foo")
  }

  @Test
  def shouldReceiveThreeConsumerMethodRegisteredEvents = {
    Actor.registry.addListener(requestor)
    val latch = Await.result((publisher ? SetExpectedTestMessageCount(3)).mapTo[CountDownLatch], 3 seconds)
    val obj = TypedActor.typedActorOf(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl], Props())
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val request = GetRetainedMessages(_.isInstanceOf[ConsumerMethodRegistered])
    val events = Await.result((publisher ? request).mapTo[List[ConsumerMethodRegistered]], 3 seconds)
    assert(events.map(_.method.getName).sortWith(_ < _) === List("m2", "m3", "m4"))
  }

  @Test
  def shouldReceiveThreeConsumerMethodUnregisteredEvents = {
    val obj = TypedActor.typedActorOf(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl], Props())
    val latch = Await.result((publisher ? SetExpectedTestMessageCount(3)).mapTo[CountDownLatch], 3 seconds)
    Actor.registry.addListener(requestor)
    TypedActor.stop(obj)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val request = GetRetainedMessages(_.isInstanceOf[ConsumerMethodUnregistered])
    val events = Await.result((publisher ? request).mapTo[List[ConsumerMethodUnregistered]], 3 seconds)
    assert(events.map(_.method.getName).sortWith(_ < _) === List("m2", "m3", "m4"))
  }
}

object TypedConsumerPublishRequestorTest {
  class TypedConsumerPublisherMock extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }
}

