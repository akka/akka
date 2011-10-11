/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.event

import org.scalatest.{ WordSpec, BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor._
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic._
import akka.actor.{ Props, Actor, ActorRef }

object EventBusSpec {

}

abstract class EventBusSpec(busName: String) extends WordSpec with MustMatchers with TestKit with BeforeAndAfterEach {
  import EventBusSpec._
  type BusType <: EventBus

  def createNewEventBus(): BusType

  def createEvents(numberOfEvents: Int): Iterable[BusType#Event]

  def createSubscriber(pipeTo: ActorRef): BusType#Subscriber

  def classifierFor(event: BusType#Event): BusType#Classifier

  def disposeSubscriber(subscriber: BusType#Subscriber): Unit

  busName must {

    def createNewSubscriber() = createSubscriber(testActor).asInstanceOf[bus.Subscriber]
    def getClassifierFor(event: BusType#Event) = classifierFor(event).asInstanceOf[bus.Classifier]
    def createNewEvents(numberOfEvents: Int): Iterable[bus.Event] = createEvents(numberOfEvents).asInstanceOf[Iterable[bus.Event]]

    val bus = createNewEventBus()
    val events = createNewEvents(100)
    val event = events.head
    val classifier = getClassifierFor(event)
    val subscriber = createNewSubscriber()

    "allow subscribers" in {
      bus.subscribe(subscriber, classifier) must be === true
    }

    "allow to unsubscribe already existing subscriber" in {
      bus.unsubscribe(subscriber, classifier) must be === true
    }

    "not allow to unsubscribe non-existing subscriber" in {
      val sub = createNewSubscriber()
      bus.unsubscribe(sub, classifier) must be === false
      disposeSubscriber(sub)
    }

    "not allow for the same subscriber to subscribe to the same channel twice" in {
      bus.subscribe(subscriber, classifier) must be === true
      bus.subscribe(subscriber, classifier) must be === false
      bus.unsubscribe(subscriber, classifier) must be === true
    }

    "not allow for the same subscriber to unsubscribe to the same channel twice" in {
      bus.subscribe(subscriber, classifier) must be === true
      bus.unsubscribe(subscriber, classifier) must be === true
      bus.unsubscribe(subscriber, classifier) must be === false
    }

    "allow to add multiple subscribers" in {
      val subscribers = (1 to 10) map { _ ⇒ createNewSubscriber() }
      val events = createEvents(10)
      val classifiers = events map getClassifierFor
      subscribers.zip(classifiers) forall { case (s, c) ⇒ bus.subscribe(s, c) } must be === true
      subscribers.zip(classifiers) forall { case (s, c) ⇒ bus.unsubscribe(s, c) } must be === true

      subscribers foreach disposeSubscriber
    }

    "publishing events without any subscribers shouldn't be a problem" in {
      bus.publish(event)
    }

    "publish the given event to the only subscriber" in {
      bus.subscribe(subscriber, classifier)
      bus.publish(event)
      expectMsg(event)
      bus.unsubscribe(subscriber, classifier)
    }

    "publish to the only subscriber multiple times" in {
      bus.subscribe(subscriber, classifier)
      bus.publish(event)
      bus.publish(event)
      bus.publish(event)
      expectMsg(event)
      expectMsg(event)
      expectMsg(event)
      bus.unsubscribe(subscriber, classifier)
    }

    "publish the given event to all intended subscribers" in {
      val subscribers = Vector.fill(10)(createNewSubscriber())
      subscribers foreach { s ⇒ bus.subscribe(s, classifier) must be === true }
      bus.publish(event)
      (1 to 10) foreach { _ ⇒ expectMsg(event) }
      subscribers foreach disposeSubscriber
    }

    "not publish the given event to any other subscribers than the intended ones" in {
      val otherSubscriber = createNewSubscriber()
      val otherClassifier = getClassifierFor(events.drop(1).head)
      bus.subscribe(subscriber, classifier)
      bus.subscribe(otherSubscriber, otherClassifier)
      bus.publish(event)
      expectMsg(event)
      bus.unsubscribe(subscriber, classifier)
      bus.unsubscribe(otherSubscriber, otherClassifier)
      expectNoMsg(1 second)
    }

    "not publish the given event to a former subscriber" in {
      bus.subscribe(subscriber, classifier)
      bus.unsubscribe(subscriber, classifier)
      bus.publish(event)
      expectNoMsg(1 second)
    }

    "cleanup subscriber" in {
      disposeSubscriber(subscriber)
    }
  }
}

object ActorEventBusSpec {
  class ComposedActorEventBus extends ActorEventBus with LookupClassification with EventType[String] with ClassifierType[String] {
    def classify(event: String) = event
    def publish(event: String, subscriber: ActorRef) = subscriber ! event
  }

  class TestActorWrapperActor(testActor: ActorRef) extends Actor {
    def receive = {
      case x ⇒ testActor forward x
    }
  }
}

class ActorEventBusSpec extends EventBusSpec("ActorEventBus") {
  import akka.event.ActorEventBusSpec._

  type BusType = ComposedActorEventBus
  def createNewEventBus(): BusType = new ComposedActorEventBus

  def createEvents(numberOfEvents: Int) = (0 until numberOfEvents) map { _.toString }

  def createSubscriber(pipeTo: ActorRef) = actorOf(Props(new TestActorWrapperActor(pipeTo)))

  def classifierFor(event: BusType#Event) = event

  def disposeSubscriber(subscriber: BusType#Subscriber): Unit = subscriber.stop()
}
