package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import java.util.concurrent.TimeUnit
import org.multiverse.api.latches.StandardLatch
import com.rabbitmq.client.ShutdownSignalException
import akka.amqp._
import org.scalatest.matchers.MustMatchers
import akka.amqp.AMQP.{ ExchangeParameters, ChannelParameters, ProducerParameters, ConnectionParameters }
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.actor.{ Props, ActorSystem, Actor }

class AMQPProducerConnectionRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def producerConnectionRecovery = AMQPTest.withCleanEndState {

    val system = ActorSystem.create(math.random.toInt.toHexString)

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50))
    try {
      val startedLatch = new StandardLatch
      val restartingLatch = new StandardLatch
      val restartedLatch = new StandardLatch

      val producerCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ {
            if (!startedLatch.isOpen) {
              startedLatch.open
            } else {
              restartedLatch.open
            }
          }
          case Restarting ⇒ restartingLatch.open
          case Stopped    ⇒ ()
        }
      }))

      val channelParameters = ChannelParameters(channelCallback = Some(producerCallback))
      val producerParameters = ProducerParameters(
        Some(ExchangeParameters("text_exchange")), channelParameters = Some(channelParameters))

      AMQP.newProducer(connection, producerParameters)
      startedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))
      restartingLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
      restartedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      AMQP.shutdownConnection(connection)
    }
  }
}
