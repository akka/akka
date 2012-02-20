package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.amqp._
import org.multiverse.api.latches.StandardLatch
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.amqp.AMQP.{ ConsumerParameters, ChannelParameters, ProducerParameters }
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.actor.{ Props, ActorSystem, Actor }

class AMQPConsumerPrivateQueueTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = AMQPTest.withCleanEndState {
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

      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      val payloadLatch = new StandardLatch
      AMQP.newConsumer(connection, ConsumerParameters("my.private.routing.key", system.actorOf(Props(new Actor {
        def receive = { case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open }
      })), channelParameters = Some(channelParameters))).
        getOrElse(throw new NoSuchElementException("Could not create consumer"))

      val producer = AMQP.newProducer(connection,
        ProducerParameters(channelParameters = Some(channelParameters))).
        getOrElse(throw new NoSuchElementException("Could not create producer"))

      countDown.await(2, TimeUnit.SECONDS) must be(true)
      producer ! Message("some_payload".getBytes, "my.private.routing.key")
      payloadLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      AMQP.shutdownConnection(connection)
    }
  }
}