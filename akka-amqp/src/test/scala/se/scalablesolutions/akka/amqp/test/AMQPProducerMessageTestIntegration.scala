package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.ActorRef
import org.multiverse.api.latches.StandardLatch
import se.scalablesolutions.akka.amqp._
import com.rabbitmq.client.ReturnListener
import com.rabbitmq.client.AMQP.BasicProperties
import java.lang.String
import org.scalatest.matchers.MustMatchers
import se.scalablesolutions.akka.amqp.AMQP.{ExchangeParameters, ProducerParameters}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

class AMQPProducerMessageTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def producerMessage = AMQPTest.withCleanEndState {

    val connection: ActorRef = AMQP.newConnection()
    try {
      val returnLatch = new StandardLatch
      val returnListener = new ReturnListener {
        def handleBasicReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) = {
          returnLatch.open
        }
      }
      val producerParameters = ProducerParameters(
        Some(ExchangeParameters("text_exchange")), returnListener = Some(returnListener))

      val producer = AMQP.newProducer(connection, producerParameters)

      producer ! new Message("some_payload".getBytes, "non.interesing.routing.key", mandatory = true)
      returnLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      connection.stop
    }
  }
}
