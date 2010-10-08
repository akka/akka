package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import se.scalablesolutions.akka.amqp.AMQP
import org.junit.Test
import org.multiverse.api.latches.StandardLatch
import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.amqp.rpc.RPC

class AMQPStringProducerConsumerTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = AMQPTest.withCleanEndState {

    val connection = AMQP.newConnection()

    val responseLatch = new StandardLatch

    RPC.newStringRpcServer(connection, "stringexchange", requestHandler _)

    val request = "somemessage"

    def responseHandler(response: String) = {

      assert(response == request.reverse)
      responseLatch.open
    }
    AMQP.newStringConsumer(connection, responseHandler _, None, Some("string.reply.key"))

    val producer = AMQP.newStringProducer(connection, Some("stringexchange"))
    producer.send(request, Some("string.reply.key"))

    responseLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
  }

  def requestHandler(request: String): String= {
    println("###### Reverse")
    request.reverse
  }
}
