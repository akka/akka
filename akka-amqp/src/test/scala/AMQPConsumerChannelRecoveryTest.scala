/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import se.scalablesolutions.akka.actor.Actor._
import org.multiverse.api.latches.StandardLatch
import com.rabbitmq.client.ShutdownSignalException
import se.scalablesolutions.akka.amqp._
import org.scalatest.matchers.MustMatchers
import se.scalablesolutions.akka.amqp.AMQP.{ConsumerParameters, ChannelParameters, ProducerParameters, ConnectionParameters}
import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.ActorRef
import org.junit.Test

class AMQPConsumerChannelRecoveryTest extends JUnitSuite with MustMatchers with Logging {

//  @Test
  def consumerChannelRecovery = {

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50))
    try {
      val producer = AMQP.newProducer(connection, ProducerParameters(
        ChannelParameters("text_exchange", ExchangeType.Direct)))

      val consumerStartedLatch = new StandardLatch
      val consumerRestartedLatch = new StandardLatch
      val consumerChannelCallback: ActorRef = actor {
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

      val payloadLatch = new StandardLatch
      val consumerChannelParameters = ChannelParameters("text_exchange", ExchangeType.Direct, channelCallback = Some(consumerChannelCallback))
      val consumer = AMQP.newConsumer(connection, ConsumerParameters(consumerChannelParameters, "non.interesting.routing.key", actor {
        case Delivery(payload, _, _, _, _) => payloadLatch.open
      }))
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

  @Test
  def dummy {
    // amqp tests need local rabbitmq server running, so a disabled by default.
    // this dummy test makes sure that the whole test class doesn't fail because of missing tests
    assert(true)
  }
}