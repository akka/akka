package se.scalablesolutions.akka.camel.component

import ActorComponentTest._

import java.util.concurrent.{CountDownLatch, TimeoutException, TimeUnit}

import org.apache.camel.ExchangePattern
import org.junit.{After, Test}
import org.scalatest.junit.JUnitSuite
import org.scalatest.BeforeAndAfterAll

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.ActorRegistry
import se.scalablesolutions.akka.camel.{Failure, Message}
import se.scalablesolutions.akka.camel.support._

class ActorProducerTest extends JUnitSuite with BeforeAndAfterAll {
  @After def tearDown = ActorRegistry.shutdownAll

  @Test def shouldSendMessageToActor = {
    val actor = actorOf[Tester1].start
    val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOnly)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    endpoint.createProducer.process(exchange)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val reply = (actor !! GetRetainedMessage).get.asInstanceOf[Message]
    assert(reply.body === "Martin")
    assert(reply.headers === Map(Message.MessageExchangeId -> exchange.getExchangeId, "k1" -> "v1"))
  }

  @Test def shouldSendMessageToActorAndReceiveResponse = {
    val actor = actorOf(new Tester2 {
      override def response(msg: Message) = Message(super.response(msg), Map("k2" -> "v2"))
    }).start
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    endpoint.createProducer.process(exchange)
    assert(exchange.getOut.getBody === "Hello Martin")
    assert(exchange.getOut.getHeader("k2") === "v2")
  }

  @Test def shouldSendMessageToActorAndReceiveFailure = {
    val actor = actorOf(new Tester2 {
      override def response(msg: Message) = Failure(new Exception("testmsg"), Map("k3" -> "v3"))
    }).start
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    endpoint.createProducer.process(exchange)
    assert(exchange.getException.getMessage === "testmsg")
    assert(exchange.getOut.getBody === null)
    assert(exchange.getOut.getHeader("k3") === null) // headers from failure message are currently ignored
  }

  @Test def shouldSendMessageToActorAndTimeout(): Unit = {
    val actor = actorOf[Tester3].start
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    exchange.getIn.setBody("Martin")
    intercept[TimeoutException] {
      endpoint.createProducer.process(exchange)
    }
  }
}
