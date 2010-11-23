package akka.camel

import org.junit.Test
import org.scalatest.junit.JUnitSuite
import akka.actor.{ActorRef, Actor, UntypedActor}

class ConsumerRegisteredTest extends JUnitSuite {
  import ConsumerRegisteredTest._

  @Test def shouldCreateSomeNonBlockingPublishRequestFromConsumer = {
    val c = Actor.actorOf[ConsumerActor1]
    val event = ConsumerActorRegistered.forConsumer(c)
    assert(event === Some(ConsumerActorRegistered(c, consumerOf(c))))
  }

  @Test def shouldCreateSomeBlockingPublishRequestFromConsumer = {
    val c = Actor.actorOf[ConsumerActor2]
    val event = ConsumerActorRegistered.forConsumer(c)
    assert(event === Some(ConsumerActorRegistered(c, consumerOf(c))))
  }

  @Test def shouldCreateNoneFromConsumer = {
    val event = ConsumerActorRegistered.forConsumer(Actor.actorOf[PlainActor])
    assert(event === None)
  }

  @Test def shouldCreateSomeNonBlockingPublishRequestFromUntypedConsumer = {
    val uc = UntypedActor.actorOf(classOf[SampleUntypedConsumer])
    val event = ConsumerActorRegistered.forConsumer(uc)
    assert(event === Some(ConsumerActorRegistered(uc, consumerOf(uc))))
  }

  @Test def shouldCreateSomeBlockingPublishRequestFromUntypedConsumer = {
    val uc = UntypedActor.actorOf(classOf[SampleUntypedConsumerBlocking])
    val event = ConsumerActorRegistered.forConsumer(uc)
    assert(event === Some(ConsumerActorRegistered(uc, consumerOf(uc))))
  }

  @Test def shouldCreateNoneFromUntypedConsumer = {
    val a = UntypedActor.actorOf(classOf[SampleUntypedActor])
    val event = ConsumerActorRegistered.forConsumer(a)
    assert(event === None)
  }

  private def consumerOf(ref: ActorRef) = ref.actor.asInstanceOf[Consumer]
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
