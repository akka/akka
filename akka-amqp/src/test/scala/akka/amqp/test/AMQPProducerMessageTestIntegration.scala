/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import org.scalatest.matchers.MustMatchers
import akka.amqp._
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import akka.util.Timeout
import akka.pattern.ask
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.dispatch.Await
import akka.testkit.TestLatch
import com.rabbitmq.client.ReturnListener
import com.rabbitmq.client.AMQP.BasicProperties

class AMQPProducerMessageTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def producerMessage = AMQPTest.withCleanEndState((mySys: ActorSystem) ⇒ {

    //TODO is there a cleaner way to handle this implicit declaration?
    implicit val system = mySys
    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    try {
      val amqp = system.actorOf(Props[AMQPActor])
      val connectionFuture = (amqp ? ConnectionRequest(ConnectionParameters(initReconnectDelay = 50))) mapTo manifest[ActorRef]
      val connection = Await.result(connectionFuture, timeout.duration)

      val returnLatch = new TestLatch
      val startedLatch = new TestLatch

      def channelCallback(startedLatch: TestLatch) = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ startedLatch.open
          case _       ⇒ ()
        }
      }))

      val producerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(startedLatch)))

      val returnListener = new ReturnListener {
        def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) = {
          returnLatch.open
        }
      }
      val producerParameters = ProducerParameters(
        Some(ExchangeParameters("text_exchange")), returnListener = Some(returnListener), channelParameters = Some(producerChannelParameters))

      val producer = Await.result((connection ? ProducerRequest(producerParameters))
        mapTo manifest[ActorRef], timeout.duration)

      Await.result(startedLatch, timeout.duration)

      producer ! new Message("some_payload".getBytes, "non.interesing.routing.key", mandatory = true)

      Await.result(returnLatch, timeout.duration)

    } catch {
      case e: Exception ⇒ fail(e)
    } finally {
      system.shutdown
    }
  })
}
