package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import org.multiverse.api.latches.StandardLatch
import com.rabbitmq.client.ShutdownSignalException
import se.scalablesolutions.akka.amqp._
import org.scalatest.matchers.MustMatchers
import se.scalablesolutions.akka.amqp.AMQP.{ExchangeParameters, ChannelParameters, ProducerParameters, ConnectionParameters}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

class AMQPProducerChannelRecoveryTest extends JUnitSuite with MustMatchers {

  @Test
  def producerChannelRecovery = if (AMQPTest.enabled) AMQPTest.withCleanEndState {

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

      val channelParameters = ChannelParameters(channelCallback = Some(producerCallback))
      val producerParameters = ProducerParameters(
        ExchangeParameters("text_exchange", ExchangeType.Direct), channelParameters = Some(channelParameters))

      val producer = AMQP.newProducer(connection, producerParameters)
      startedLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)

      producer ! new ChannelShutdown(new ShutdownSignalException(false, false, "TestException", "TestRef"))
      restartingLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
      restartedLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }
}
