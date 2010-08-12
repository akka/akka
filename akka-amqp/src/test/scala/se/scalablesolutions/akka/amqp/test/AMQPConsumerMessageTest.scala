package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import se.scalablesolutions.akka.amqp._
import org.multiverse.api.latches.StandardLatch
import se.scalablesolutions.akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.{CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.amqp.AMQP.{ExchangeParameters, ConsumerParameters, ChannelParameters, ProducerParameters}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

class AMQPConsumerMessageTest extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = if (AMQPTest.enabled) AMQPTest.withCleanEndState {
    val connection = AMQP.newConnection()
    try {

      val countDown = new CountDownLatch(2)
      val channelCallback = actor {
        case Started => countDown.countDown
        case Restarting => ()
        case Stopped => ()
      }

      val exchangeParameters = ExchangeParameters("text_exchange",ExchangeType.Direct)
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      val payloadLatch = new StandardLatch
      val consumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "non.interesting.routing.key", actor {
        case Delivery(payload, _, _, _, _) => payloadLatch.open
      }, channelParameters = Some(channelParameters)))

      val producer = AMQP.newProducer(connection,
        ProducerParameters(exchangeParameters, channelParameters = Some(channelParameters)))

      countDown.await(2, TimeUnit.SECONDS) must be (true)
      producer ! Message("some_payload".getBytes, "non.interesting.routing.key")
      payloadLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }
}
