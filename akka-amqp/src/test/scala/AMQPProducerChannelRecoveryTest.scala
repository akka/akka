/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import junit.framework.Assert
import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import org.multiverse.api.latches.StandardLatch
import com.rabbitmq.client.ShutdownSignalException
import se.scalablesolutions.akka.amqp._
import se.scalablesolutions.akka.amqp.AMQP.{ChannelParameters, ProducerParameters, ConnectionParameters}
import org.scalatest.matchers.MustMatchers

class AMQPProducerChannelRecoveryTest extends JUnitSuite with MustMatchers with Logging {

//  @Test
  def producerChannelRecovery = {

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50))

    try {
      val startedLatch = new StandardLatch
      val restartingLatch = new StandardLatch
      val restartedLatch = new StandardLatch

      val producerCallback: ActorRef = Actor.actor({
        case Started => {
          if (!startedLatch.isOpen) {
            startedLatch.open
          } else {
            restartedLatch.open
          }
        }
        case Restarting => restartingLatch.open
        case Stopped => ()
      })

      val producerParameters = ProducerParameters(
        ChannelParameters("text_exchange", ExchangeType.Direct, channelCallback = Some(producerCallback)))

      val producer = AMQP.newProducer(connection, producerParameters)
      startedLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)

      producer ! new ChannelShutdown(new ShutdownSignalException(false, false, "TestException", "TestRef"))
      restartingLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
      restartedLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
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