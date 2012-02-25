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
import akka.pattern.ask
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.dispatch.Await
import akka.testkit.TestLatch

class AMQPConsumerConnectionRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerConnectionRecovery = AMQPTest.withCleanEndState((sys: ActorSystem) ⇒ {

    //TODO is there a cleaner way to handle this implicit declaration?
    implicit val system = sys
    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    try {
      // get an AMQP root actor
      val amqp = system.actorOf(Props[AMQPActor])
      val connectionFuture = (amqp ? ConnectionRequest(ConnectionParameters(initReconnectDelay = 50))) mapTo manifest[ActorRef]
      val connection = Await.result(connectionFuture, timeout.duration)

      val producerStartedLatch = new TestLatch
      val producerRestartedLatch = new TestLatch
      val consumerStartedLatch = new TestLatch
      val consumerRestartedLatch = new TestLatch
      val payloadLatch = new TestLatch

      val producerChannelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ {
            if (!producerStartedLatch.isOpen) {
              producerStartedLatch.open
            } else {
              producerRestartedLatch.open
            }
          }
          case Restarting ⇒ ()
          case Stopped    ⇒ ()
        }
      }))

      val channelParameters = ChannelParameters(channelCallback = Some(producerChannelCallback))

      val producer = Await.result((connection ? ProducerRequest(ProducerParameters(
        Some(ExchangeParameters("text_exchange")), channelParameters = Some(channelParameters))))
        mapTo manifest[ActorRef], timeout.duration)

      Await.result(producerStartedLatch, timeout.duration)

      val consumerChannelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ {
            if (!consumerStartedLatch.isOpen) {
              consumerStartedLatch.open
            } else {
              consumerRestartedLatch.open
            }
          }
          case Restarting ⇒ ()
          case Stopped    ⇒ ()
        }
      }))

      val consumerExchangeParameters = ExchangeParameters("text_exchange")
      val consumerChannelParameters = ChannelParameters(channelCallback = Some(consumerChannelCallback))

      Await.result((connection ? ConsumerRequest(
        ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
          def receive = { case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open }
        })), exchangeParameters = Some(consumerExchangeParameters), channelParameters = Some(consumerChannelParameters))))
        mapTo manifest[ActorRef], timeout.duration)

      Await.result(consumerStartedLatch, timeout.duration)

      println("about to send connection shutdown message.")

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))

      Await.result(producerRestartedLatch, timeout.duration)
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
