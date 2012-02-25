/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import com.rabbitmq.client.ShutdownSignalException
import akka.amqp._
import org.scalatest.matchers.MustMatchers
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import akka.util.Timeout
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.pattern.ask
import akka.dispatch.Await
import akka.testkit.TestLatch

class AMQPConsumerChannelRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerChannelRecovery = AMQPTest.withCleanEndState((sys: ActorSystem) ⇒ {

    //TODO is there a cleaner way to handle this implicit declaration?
    implicit val system = sys
    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    try {
      // get an AMQP root actor
      val amqp = system.actorOf(Props[AMQPActor])
      val connectionFuture = (amqp ? ConnectionRequest(ConnectionParameters(initReconnectDelay = 50))) mapTo manifest[ActorRef]
      val connection = Await.result(connectionFuture, timeout.duration)

      val producer = Await.result((connection ? ProducerRequest(ProducerParameters(Some(ExchangeParameters("text_exchange")))))
        mapTo manifest[ActorRef], timeout.duration)

      val consumerStartedLatch = new TestLatch
      val consumerRestartedLatch = new TestLatch
      val consumerChannelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ {
            if (!consumerStartedLatch.isOpen) {
              consumerStartedLatch.open
            } else {
              consumerRestartedLatch.open
            }
          }
          case Restarting ⇒ println("*** restarting ***")
          case Stopped    ⇒ ()
        }
      }))

      val payloadLatch = new TestLatch
      val consumerExchangeParameters = ExchangeParameters("text_exchange")
      val consumerChannelParameters = ChannelParameters(channelCallback = Some(consumerChannelCallback))

      Await.result((connection ? ConsumerRequest(
        ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
          def receive = { case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open }
        })), exchangeParameters = Some(consumerExchangeParameters), channelParameters = Some(consumerChannelParameters))))
        mapTo manifest[ActorRef], timeout.duration)

      Await.result(consumerStartedLatch, timeout.duration)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))

      Await.result(consumerRestartedLatch, timeout.duration)

      producer ! Message("some_payload".getBytes, "non.interesting.routing.key")

      Await.result(payloadLatch, timeout.duration)
    } catch {
      case e: Exception ⇒ fail(e)
    } finally {
      system.shutdown()
    }
  })
}
