package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import akka.amqp._
import org.junit.Test
import java.util.concurrent.{CountDownLatch, TimeUnit}
import akka.amqp.AMQP.{ExchangeParameters, ConsumerParameters, ChannelParameters, ProducerParameters}
import org.multiverse.api.latches.StandardLatch
import org.scalatest.junit.JUnitSuite
import akka.actor.{Actor, ActorRef}

class AMQPConsumerManualRejectTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessageManualAcknowledge = AMQPTest.withCleanEndState {
    val connection = AMQP.newConnection()
    try {
      val countDown = new CountDownLatch(2)
      val restartingLatch = new StandardLatch
      val channelCallback = actorOf(new Actor {
        def receive = {
          case Started => countDown.countDown
          case Restarting => restartingLatch.open
          case Stopped => ()
        }
      }).start
      val exchangeParameters = ExchangeParameters("text_exchange")
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      val rejectedLatch = new StandardLatch
      val consumer:ActorRef = AMQP.newConsumer(connection, ConsumerParameters("manual.reject.this", actorOf( new Actor {
        def receive = {
          case Delivery(payload, _, deliveryTag, _, sender) => sender.foreach(_ ! Reject(deliveryTag))
          case Rejected(deliveryTag) => rejectedLatch.open
        }
      }).start, queueName = Some("self.reject.queue"), exchangeParameters = Some(exchangeParameters),
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
