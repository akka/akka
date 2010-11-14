package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import akka.amqp._
import org.junit.Test
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.multiverse.api.latches.StandardLatch
import org.scalatest.junit.JUnitSuite
import akka.amqp.AMQP._
import akka.actor.{Actor, ActorRef}

class AMQPConsumerManualAcknowledgeTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessageManualAcknowledge = AMQPTest.withCleanEndState {
    val connection = AMQP.newConnection()
    try {
      val countDown = new CountDownLatch(2)
      val channelCallback = actorOf( new Actor {
        def receive = {
          case Started => countDown.countDown
          case Restarting => ()
          case Stopped => ()
        }
      }).start
      val exchangeParameters = ExchangeParameters("text_exchange")
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      val failLatch = new StandardLatch
      val acknowledgeLatch = new StandardLatch
      var deliveryTagCheck: Long = -1
      val consumer:ActorRef = AMQP.newConsumer(connection, ConsumerParameters("manual.ack.this", actorOf( new Actor {
        def receive = {
          case Delivery(payload, _, deliveryTag, _, _, sender) => {
            if (!failLatch.isOpen) {
              failLatch.open
              error("Make it fail!")
            } else {
              deliveryTagCheck = deliveryTag
              sender.foreach(_ ! Acknowledge(deliveryTag))
            }
          }
          case Acknowledged(deliveryTag) => if (deliveryTagCheck == deliveryTag) acknowledgeLatch.open
        }
      }), queueName = Some("self.ack.queue"), exchangeParameters = Some(exchangeParameters),
        selfAcknowledging = false, channelParameters = Some(channelParameters),
        queueDeclaration = ActiveDeclaration(autoDelete = false)))

      val producer = AMQP.newProducer(connection,
        ProducerParameters(Some(exchangeParameters), channelParameters = Some(channelParameters)))

      countDown.await(2, TimeUnit.SECONDS) must be (true)
      producer ! Message("some_payload".getBytes, "manual.ack.this")

      acknowledgeLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }
}
