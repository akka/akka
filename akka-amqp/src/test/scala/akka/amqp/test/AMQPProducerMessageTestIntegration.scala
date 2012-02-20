package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import java.util.concurrent.TimeUnit
import org.multiverse.api.latches.StandardLatch
import akka.amqp._
import com.rabbitmq.client.ReturnListener
import com.rabbitmq.client.AMQP.BasicProperties
import java.lang.String
import org.scalatest.matchers.MustMatchers
import akka.amqp.AMQP.{ ExchangeParameters, ProducerParameters }
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.actor.ActorSystem
import akka.event.{ LogSource, Logging }

class AMQPProducerMessageTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def producerMessage = AMQPTest.withCleanEndState {

    implicit val logSourceString = LogSource.fromString

    val system = ActorSystem.create(math.random.toInt.toHexString)

    val log = Logging(system, this.getClass.getName)

    log.info("in producerMessage")

    val connection = AMQP.newConnection()

    log.info("connection actor path = " + connection.path.toString)

    try {
      val returnLatch = new StandardLatch
      val returnListener = new ReturnListener {
        def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) = {
          returnLatch.open
        }
      }
      val producerParameters = ProducerParameters(
        Some(ExchangeParameters("text_exchange")), returnListener = Some(returnListener))

      val producer = AMQP.newProducer(connection, producerParameters).
        getOrElse(throw new NoSuchElementException("Could not create producer"))

      log.info("producer path = {}", producer.path.toString)

      producer ! new Message("some_payload".getBytes, "non.interesing.routing.key", mandatory = true)
      returnLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      AMQP.shutdownConnection(connection)
    }
  }
}
