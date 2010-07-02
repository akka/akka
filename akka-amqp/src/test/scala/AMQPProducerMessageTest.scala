/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.ActorRef
import org.multiverse.api.latches.StandardLatch
import se.scalablesolutions.akka.amqp._
import com.rabbitmq.client.ReturnListener
import com.rabbitmq.client.AMQP.BasicProperties
import java.lang.String
import se.scalablesolutions.akka.amqp.AMQP.{ChannelParameters, ProducerParameters}
import org.scalatest.matchers.MustMatchers

class AMQPProducerMessageTest extends JUnitSuite with MustMatchers with Logging {

//  @Test
  def producerMessage = {
    
    val connection: ActorRef = AMQP.newConnection()
    try {
      val returnLatch = new StandardLatch
      val returnListener = new ReturnListener {
        def handleBasicReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) = {
          returnLatch.open
        }
      }
      val producerParameters = ProducerParameters(
        ChannelParameters("text_exchange", ExchangeType.Direct),
        returnListener = Some(returnListener))

      val producer = AMQP.newProducer(connection, producerParameters)

      producer ! new Message("some_payload".getBytes, "non.interesing.routing.key", mandatory = true)
      returnLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
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