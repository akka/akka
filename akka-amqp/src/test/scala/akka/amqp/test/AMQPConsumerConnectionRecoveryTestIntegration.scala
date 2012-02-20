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
import akka.actor.{ Props, ActorSystem, Actor }

class AMQPConsumerConnectionRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerConnectionRecovery = AMQPTest.withCleanEndState {

    val system = ActorSystem.create("consumerConnectionRecovery")

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50))
    try {
      val producerStartedLatch = new StandardLatch
      val producerRestartedLatch = new StandardLatch
      val producerChannelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ {
            if (!producerStartedLatch.isOpen) {
              producerStartedLatch.open
            } else {
              producerRestartedLatch.open
            }
          }
          case Restarting ⇒ ()
          case Stopped    ⇒ ()
        }
      }))

      val channelParameters = ChannelParameters(channelCallback = Some(producerChannelCallback))
      val producer = AMQP.newProducer(connection, ProducerParameters(
        Some(ExchangeParameters("text_exchange")), channelParameters = Some(channelParameters))).
        getOrElse(throw new NoSuchElementException("Could not create producer"))

      producerStartedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

      val consumerStartedLatch = new StandardLatch
      val consumerRestartedLatch = new StandardLatch
      val consumerChannelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ {
            if (!consumerStartedLatch.isOpen) {
              consumerStartedLatch.open
            } else {
              consumerRestartedLatch.open
            }
          }
          case Restarting ⇒ ()
          case Stopped    ⇒ ()
        }
      }))

      val payloadLatch = new StandardLatch
      val consumerExchangeParameters = ExchangeParameters("text_exchange")
      val consumerChannelParameters = ChannelParameters(channelCallback = Some(consumerChannelCallback))
      AMQP.newConsumer(connection, ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
        def receive = { case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open }
      })), exchangeParameters = Some(consumerExchangeParameters), channelParameters = Some(consumerChannelParameters)))

      consumerStartedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))

      producerRestartedLatch.tryAwait(4, TimeUnit.SECONDS) must be(true)
      consumerRestartedLatch.tryAwait(4, TimeUnit.SECONDS) must be(true)

      producer ! Message("some_payload".getBytes, "non.interesting.routing.key")
      payloadLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      AMQP.shutdownConnection(connection)
    }
  }
}
