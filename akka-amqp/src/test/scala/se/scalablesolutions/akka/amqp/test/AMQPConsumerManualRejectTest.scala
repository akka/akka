package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import se.scalablesolutions.akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import se.scalablesolutions.akka.amqp._
import org.junit.Test
import se.scalablesolutions.akka.actor.ActorRef
import java.util.concurrent.{CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.amqp.AMQP.{ExchangeParameters, ConsumerParameters, ChannelParameters, ProducerParameters}
import org.multiverse.api.latches.StandardLatch
import org.scalatest.junit.JUnitSuite

class AMQPConsumerManualRejectTest extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessageManualAcknowledge = if (AMQPTest.enabled) AMQPTest.withCleanEndState {
    val connection = AMQP.newConnection()
    try {
      val countDown = new CountDownLatch(2)
      val restartingLatch = new StandardLatch
      val channelCallback = actor {
        case Started => countDown.countDown
        case Restarting => restartingLatch.open
        case Stopped => ()
      }
      val exchangeParameters = ExchangeParameters("text_exchange")
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      val rejectedLatch = new StandardLatch
      val consumer:ActorRef = AMQP.newConsumer(connection, ConsumerParameters("manual.reject.this", actor {
        case Delivery(payload, _, deliveryTag, _, sender) => {
          sender.foreach(_ ! Reject(deliveryTag))
        }
        case Rejected(deliveryTag) => rejectedLatch.open
      }, queueName = Some("self.reject.queue"), exchangeParameters = Some(exchangeParameters),
        selfAcknowledging = false, channelParameters = Some(channelParameters)))

      val producer = AMQP.newProducer(connection,
        ProducerParameters(Some(exchangeParameters), channelParameters = Some(channelParameters)))

      countDown.await(2, TimeUnit.SECONDS) must be (true)
      producer ! Message("some_payload".getBytes, "manual.reject.this")

      rejectedLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
      restartingLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }
}
