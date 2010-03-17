package se.scalablesolutions.akka.camel.component

import org.apache.camel.{CamelContext, ExchangePattern}
import org.apache.camel.impl.{DefaultCamelContext, DefaultExchange}
import org.junit.Assert._
import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.camel.Message

class ActorProducerTest extends JUnitSuite {

  //
  // TODO: extend/rewrite unit tests
  // These tests currently only ensure proper functioning of basic features.
  //

  val context = new DefaultCamelContext
  val endpoint = context.getEndpoint("actor:%s" format classOf[TestActor].getName)
  val producer = endpoint.createProducer

  @Test def shouldSendAndReceiveMessageBodyAndHeaders = {
    val exchange = new DefaultExchange(null.asInstanceOf[CamelContext], ExchangePattern.InOut)
    val actor = new TestActor
    actor.start
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    producer.process(exchange)
    assertEquals("Hello Martin", exchange.getOut.getBody)
    assertEquals("v1", exchange.getOut.getHeader("k1"))
    assertEquals("v2", exchange.getOut.getHeader("k2"))
    actor.stop
  }

  class TestActor extends Actor {
    protected def receive = {
      case msg: Message => reply(Message("Hello %s" format msg.body, Map("k2" -> "v2") ++ msg.headers))
    }
  }

}
