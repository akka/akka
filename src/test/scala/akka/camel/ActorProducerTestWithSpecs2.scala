package akka.camel

import component.{Path, ActorEndpointConfig, TestableProducer}
import akka.actor.{Props, ActorSystem, Actor}
import java.util.concurrent.{TimeUnit, CountDownLatch}
import java.util.concurrent.atomic.AtomicReference
import org.specs2.mock._
import org.specs2.mutable.Specification

//TODO: decide on ScalaTest vs Specs2 and delete either this test or ActorProducerTest
class ActorProducerTestWithSpecs2 extends Specification with Mockito {

  val registry = mock[ConsumerRegistry]
  val config = new ActorEndpointConfig {
    val path = Path("some actor path")
    val getEndpointUri = ""
  }
  val producer = new TestableProducer(config, registry)
  val exchange = mock[CamelExchangeAdapter]
  val message = new Message()
  val system = ActorSystem("test")

  def startSimpleActor = {
    val receivedLatch = new CountDownLatch(1)
    val message = new AtomicReference[Any]()
    val actor = system.actorOf(Props(new Actor {
      protected def receive = {
        case m => receivedLatch.countDown(); message.set(m)
      }
    }))
    (receivedLatch, message,  actor)
  }

  "ActorProducer" should {
    "pass the message to the consumer, when exchange is synchronous and in-only" in {
      val (receivedLatch, receivedMessage,  actor) = startSimpleActor

      registry.findConsumer(config.path) returns Option(actor)
      exchange.toRequestMessage(any[Map[String, Any]]) returns message
      exchange.isOutCapable returns false

      producer.process(exchange)

      if (!receivedLatch.await(1, TimeUnit.SECONDS)) failure("Expected to get a message but got non within the timeout");
      receivedMessage.get() must be equalTo (message)
    }

    "get a response, when exchange is synchronous" in {
      val actor = system.actorOf(Props(new Actor {
        protected def receive = { case msg => sender ! "received "+msg}
      }))

      registry.findConsumer(config.path) returns Option(actor)
      exchange.toRequestMessage(any[Map[String, Any]]) returns message
      exchange.isOutCapable returns true

      producer.process(exchange)
      there was one(exchange).fromResponseMessage(new Message("received "+message))
    }

  }


}