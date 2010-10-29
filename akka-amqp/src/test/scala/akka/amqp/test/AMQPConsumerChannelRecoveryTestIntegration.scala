package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import org.multiverse.api.latches.StandardLatch
import com.rabbitmq.client.ShutdownSignalException
import akka.amqp._
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.TimeUnit
import org.junit.Test
import akka.amqp.AMQP._
import org.scalatest.junit.JUnitSuite
import akka.actor.Actor._
import akka.actor.{Actor, ActorRef}

class AMQPConsumerChannelRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerChannelRecovery = AMQPTest.withCleanEndState {

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50))
    try {
      val producer = AMQP.newProducer(connection, ProducerParameters(
        Some(ExchangeParameters("text_exchange"))))

      val consumerStartedLatch = new StandardLatch
      val consumerRestartedLatch = new StandardLatch
      val consumerChannelCallback: ActorRef = actorOf( new Actor {
        def receive = {
          case Started => {
            if (!consumerStartedLatch.isOpen) {
              consumerStartedLatch.open
            } else {
              consumerRestartedLatch.open
            }
          }
          case Restarting => ()
          case Stopped => ()
        }
      }).start

      val payloadLatch = new StandardLatch
      val consumerExchangeParameters = ExchangeParameters("text_exchange")
      val consumerChannelParameters = ChannelParameters(channelCallback = Some(consumerChannelCallback))
      val consumer = AMQP.newConsumer(connection, ConsumerParameters("non.interesting.routing.key", actorOf( new Actor {
        def receive = { case Delivery(payload, _, _, _, _) => payloadLatch.open }
      }).start,
        exchangeParameters = Some(consumerExchangeParameters), channelParameters = Some(consumerChannelParameters)))
      consumerStartedLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)

      val listenerLatch = new StandardLatch

      consumer ! new ChannelShutdown(new ShutdownSignalException(false, false, "TestException", "TestRef"))

      consumerRestartedLatch.tryAwait(4, TimeUnit.SECONDS) must be (true)

      producer ! Message("some_payload".getBytes, "non.interesting.routing.key")
      payloadLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }
}
