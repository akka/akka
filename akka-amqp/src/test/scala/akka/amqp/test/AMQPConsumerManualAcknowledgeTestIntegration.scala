package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import org.scalatest.matchers.MustMatchers
import akka.amqp._
import org.junit.Test
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import org.multiverse.api.latches.StandardLatch
import org.scalatest.junit.JUnitSuite
import akka.amqp.AMQP._
import akka.actor.{ Props, ActorSystem, Actor }
import akka.event.LogSource

class AMQPConsumerManualAcknowledgeTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessageManualAcknowledge = AMQPTest.withCleanEndState {

    val system = ActorSystem.create

    import akka.event.Logging
    implicit val logSource = LogSource.fromString

    val log = Logging(system, "AMQPConsumerManualAcknowledgeTestIntegration")

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

      val failLatch = new StandardLatch
      val acknowledgeLatch = new StandardLatch
      var deliveryTagCheck: Long = -1
      val consumer = AMQP.newConsumer(connection, ConsumerParameters("manual.ack.this", system.actorOf(Props(new Actor {
        import akka.event.Logging
        def receive = {
          case Delivery(payload, _, deliveryTag, _, _, sender) ⇒ {
            val log = Logging(context.system, this)
            log.info("received delivery, sender = " + sender)
            if (!failLatch.isOpen) {
              failLatch.open
              sys.error("Make it fail!")
            } else {
              deliveryTagCheck = deliveryTag
              sender.foreach(_ ! Acknowledge(deliveryTag))
            }
          }
          case Acknowledged(deliveryTag) ⇒ {
            val log = Logging(context.system, this)
            log.info("received Acknowledged")
            if (deliveryTagCheck == deliveryTag) acknowledgeLatch.open
          }
          case _ ⇒ {
            val log = Logging(context.system, this)
            log.info("recieved unknown message")
          }
        }
      })), queueName = Some("self.ack.queue"), exchangeParameters = Some(exchangeParameters),
        selfAcknowledging = false, channelParameters = Some(channelParameters),
        queueDeclaration = ActiveDeclaration(autoDelete = false))).
        getOrElse(throw new NoSuchElementException("Could not create consumer"))

      log.info("created consumer with path {}", consumer.path.toString)

      val producer = AMQP.newProducer(connection,
        ProducerParameters(Some(exchangeParameters), channelParameters = Some(channelParameters))).
        getOrElse(throw new NoSuchElementException("Could not create producer"))

      log.info("created producer with path {}", producer.path.toString)

      countDown.await(2, TimeUnit.SECONDS) must be(true)

      log.info("countdown latch satisfied, sending message to producer")

      producer ! Message("some_payload".getBytes, "manual.ack.this")

      acknowledgeLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    } finally {
      AMQP.shutdownConnection(connection)
    }
  }
}
