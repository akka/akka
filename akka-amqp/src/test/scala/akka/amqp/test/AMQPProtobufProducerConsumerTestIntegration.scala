package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import akka.amqp.AMQP
import org.junit.Test
import org.multiverse.api.latches.StandardLatch
import java.util.concurrent.TimeUnit
import akka.amqp.AkkaAmqp.TestMessage
import akka.actor.ActorSystem
import akka.event.{ LogSource, Logging }

class AMQPProtobufProducerConsumerTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = AMQPTest.withCleanEndState {

    implicit val logSourceString = LogSource.fromString

    val system = ActorSystem.create(math.random.toInt.toHexString)

    val log = Logging(system, this.getClass.getName)

    val connection = AMQP.newConnection()

    try {

      val requestLatch = new StandardLatch

      val request = TestMessage.newBuilder.setMessage("testmessage").build

      def requestHandler(request: TestMessage) = {
        log.info("request = " + request.toString)
        requestLatch.open
      }
      AMQP.newProtobufConsumer(connection, requestHandler _, Some("protoexchange"), None)

      val producer = AMQP.newProtobufProducer[TestMessage](connection, Some("protoexchange"))
      producer.send(request)

      requestLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      AMQP.shutdownConnection(connection)
    }
  }
}
