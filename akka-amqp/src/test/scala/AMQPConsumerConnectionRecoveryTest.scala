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

class AMQPConsumerConnectionRecoveryTest extends JUnitSuite with MustMatchers with Logging {

//  @Test
  def consumerConnectionRecovery = {

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50))
    try {
      val producerStartedLatch = new StandardLatch
      val producerRestartedLatch = new StandardLatch
      val producerChannelCallback: ActorRef = actor {
        case Started => {
          if (!producerStartedLatch.isOpen) {
            producerStartedLatch.open
          } else {
            producerRestartedLatch.open
          }
        }
        case Restarting => ()
        case Stopped => ()
      }

      val producer = AMQP.newProducer(connection, ProducerParameters(
        ChannelParameters("text_exchange", ExchangeType.Direct, channelCallback = Some(producerChannelCallback))))
      producerStartedLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)


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

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))

      producerRestartedLatch.tryAwait(4, TimeUnit.SECONDS) must be (true)
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