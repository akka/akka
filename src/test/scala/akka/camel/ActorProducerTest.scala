package akka.camel

import component.{Path, ActorEndpointConfig, TestableProducer}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{eq => the, any}
import org.mockito.Mockito._
import akka.actor.{Props, ActorSystem, Actor}
import java.util.concurrent.{TimeUnit, CountDownLatch}
import java.util.concurrent.atomic.AtomicReference

class ActorProducerTest extends FlatSpec with ShouldMatchers with MockitoSugar{

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

  "ActorProducer" should "pass the message to the consumer, when exchange is synchronous and in-only" in {
    val (receivedLatch, receivedMessage,  actor) = startSimpleActor

    when(registry.findConsumer(config.path)) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn false

    producer.process(exchange)

    if (!receivedLatch.await(1, TimeUnit.SECONDS)) fail("Expected to get a message but got non within the timeout");
    receivedMessage.get() should be(message)
  }

  it should "get a response, when exchange is synchronous and out capable" in {
    val actor = system.actorOf(Props(new Actor {
      protected def receive = { case msg => sender ! "received "+msg}
    }))


    when(registry.findConsumer(config.path)) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn true

    producer.process(exchange)

   verify(exchange).fromResponseMessage(the(new Message("received "+message)))
  }

}