package akka.camel

import org.junit.Test
import org.scalatest.junit.JUnitSuite
import akka.actor.{ ActorRef, Actor, LocalActorRef }

class ConsumerRegisteredTest extends JUnitSuite {
  import ConsumerRegisteredTest._

  @Test
  def shouldCreateSomeNonBlockingPublishRequestFromConsumer = {
    val c = Actor.actorOf(Props[ConsumerActor1]
    val event = ConsumerActorRegistered.eventFor(c)
    assert(event === Some(ConsumerActorRegistered(c, consumerOf(c))))
  }

  @Test
  def shouldCreateSomeBlockingPublishRequestFromConsumer = {
    val c = Actor.actorOf(Props[ConsumerActor2]
    val event = ConsumerActorRegistered.eventFor(c)
    assert(event === Some(ConsumerActorRegistered(c, consumerOf(c))))
  }

  @Test
  def shouldCreateNoneFromConsumer = {
    val event = ConsumerActorRegistered.eventFor(Actor.actorOf(Props[PlainActor])
    assert(event === None)
  }

  @Test
  def shouldCreateSomeNonBlockingPublishRequestFromUntypedConsumer = {
    val uc = Actor.actorOf(classOf[SampleUntypedConsumer])
    val event = ConsumerActorRegistered.eventFor(uc)
    assert(event === Some(ConsumerActorRegistered(uc, consumerOf(uc))))
  }

  @Test
  def shouldCreateSomeBlockingPublishRequestFromUntypedConsumer = {
    val uc = Actor.actorOf(classOf[SampleUntypedConsumerBlocking])
    val event = ConsumerActorRegistered.eventFor(uc)
    assert(event === Some(ConsumerActorRegistered(uc, consumerOf(uc))))
  }

  @Test
  def shouldCreateNoneFromUntypedConsumer = {
    val a = Actor.actorOf(classOf[SampleUntypedActor])
    val event = ConsumerActorRegistered.eventFor(a)
    assert(event === None)
  }

  private def consumerOf(ref: ActorRef) = ref match {
    case l: LocalActorRef ⇒ l.underlyingActorInstance.asInstanceOf[Consumer]
    case _                ⇒ null: Consumer
  }
}

object ConsumerRegisteredTest {
  class ConsumerActor1 extends Actor with Consumer {
    def endpointUri = "mock:test1"
    protected def receive = null
  }

  class ConsumerActor2 extends Actor with Consumer {
    def endpointUri = "mock:test2"
    override def blocking = true
    protected def receive = null
  }

  class PlainActor extends Actor {
    protected def receive = null
  }
}
