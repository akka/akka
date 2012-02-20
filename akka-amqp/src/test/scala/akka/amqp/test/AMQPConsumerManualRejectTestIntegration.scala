package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import org.scalatest.matchers.MustMatchers
import akka.amqp._
import org.junit.Test
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.amqp.AMQP.{ ExchangeParameters, ConsumerParameters, ChannelParameters, ProducerParameters }
import org.multiverse.api.latches.StandardLatch
import org.scalatest.junit.JUnitSuite
import akka.actor.{ Props, ActorSystem, Actor }

class AMQPConsumerManualRejectTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessageManualReject = AMQPTest.withCleanEndState {

    val system = ActorSystem.create

    val connection = AMQP.newConnection()
    try {
      val countDown = new CountDownLatch(2)
      val channelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started    ⇒ countDown.countDown
          case Restarting ⇒ ()
          case Stopped    ⇒ ()
        }
      }))
      val exchangeParameters = ExchangeParameters("text_exchange")
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      val rejectedLatch = new StandardLatch
      AMQP.newConsumer(connection, ConsumerParameters("manual.reject.this", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, deliveryTag, _, _, sender) ⇒ sender.foreach(_ ! Reject(deliveryTag))
          case Rejected(deliveryTag)                           ⇒ rejectedLatch.open
        }
      })), queueName = Some("self.reject.queue"), exchangeParameters = Some(exchangeParameters),
        selfAcknowledging = false, channelParameters = Some(channelParameters))).
        getOrElse(throw new NoSuchElementException("Could not create consumer"))

      val producer = AMQP.newProducer(connection,
        ProducerParameters(Some(exchangeParameters), channelParameters = Some(channelParameters))).
        getOrElse(throw new NoSuchElementException("Could not create producer"))

      countDown.await(2, TimeUnit.SECONDS) must be(true)
      producer ! Message("some_payload".getBytes, "manual.reject.this")

      rejectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      AMQP.shutdownConnection(connection)
    }
  }
}
