package se.scalablesolutions.akka.camel.component

import ActorComponentTest._

import java.util.concurrent.TimeoutException

import org.apache.camel.ExchangePattern
import org.junit.{After, Test}
import org.scalatest.junit.JUnitSuite
import org.scalatest.BeforeAndAfterAll

import se.scalablesolutions.akka.actor.ActorRegistry
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.camel.support.{Countdown, Retain, Tester, Respond}
import se.scalablesolutions.akka.camel.{Failure, Message}

class ActorProducerTest extends JUnitSuite with BeforeAndAfterAll {
  @After def tearDown = ActorRegistry.shutdownAll

  @Test def shouldSendMessageToActor = {
    val actor = newActor(() => new Tester with Retain with Countdown[Message])
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOnly)
    actor.start
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    endpoint.createProducer.process(exchange)
    actor.actor.asInstanceOf[Countdown[Message]].waitFor
    assert(actor.actor.asInstanceOf[Retain].body === "Martin")
    assert(actor.actor.asInstanceOf[Retain].headers === Map(Message.MessageExchangeId -> exchange.getExchangeId, "k1" -> "v1"))
  }

  @Test def shouldSendMessageToActorAndReceiveResponse = {
    val actor = newActor(() => new Tester with Respond {
      override def response(msg: Message) = Message(super.response(msg), Map("k2" -> "v2"))
    })
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    actor.start
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    endpoint.createProducer.process(exchange)
    assert(exchange.getOut.getBody === "Hello Martin")
    assert(exchange.getOut.getHeader("k2") === "v2")
  }

  @Test def shouldSendMessageToActorAndReceiveFailure = {
    val actor = newActor(() => new Tester with Respond {
      override def response(msg: Message) = Failure(new Exception("testmsg"), Map("k3" -> "v3"))
    })
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    actor.start
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    endpoint.createProducer.process(exchange)
    assert(exchange.getException.getMessage === "testmsg")
    assert(exchange.getOut.getBody === null)
    assert(exchange.getOut.getHeader("k3") === null) // headers from failure message are currently ignored
  }

  @Test def shouldSendMessageToActorAndTimeout: Unit = {
    val actor = newActor(() => new Tester {
      timeout = 1
    })
    val endpoint = mockEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    actor.start
    exchange.getIn.setBody("Martin")
    intercept[TimeoutException] {
      endpoint.createProducer.process(exchange)
    }
  }
}
