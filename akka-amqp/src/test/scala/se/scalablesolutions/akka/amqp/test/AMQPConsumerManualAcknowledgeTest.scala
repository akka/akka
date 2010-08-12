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

class AMQPConsumerManualAcknowledgeTest extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessageManualAcknowledge = if (AMQPTest.enabled) AMQPTest.withCleanEndState {
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

      val failLatch = new StandardLatch
      val acknowledgeLatch = new StandardLatch
      var deliveryTagCheck: Long = -1
      val consumer:ActorRef = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "manual.ack.this", actor {
        case Delivery(payload, _, deliveryTag, _, sender) => {
          if (!failLatch.isOpen) {
            failLatch.open
            error("Make it fail!")
          } else {
            deliveryTagCheck = deliveryTag
            sender.foreach(_ ! Acknowledge(deliveryTag))
          }
        }
        case Acknowledged(deliveryTag) => if (deliveryTagCheck == deliveryTag) acknowledgeLatch.open
      }, queueName = Some("self.ack.queue"), selfAcknowledging = false, queueAutoDelete = false, channelParameters = Some(channelParameters)))

      val producer = AMQP.newProducer(connection,
        ProducerParameters(exchangeParameters, channelParameters = Some(channelParameters)))

      countDown.await(2, TimeUnit.SECONDS) must be (true)
      producer ! Message("some_payload".getBytes, "manual.ack.this")

      acknowledgeLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }
}
