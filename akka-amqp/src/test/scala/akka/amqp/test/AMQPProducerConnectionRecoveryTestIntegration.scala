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
import com.rabbitmq.client.ShutdownSignalException

class AMQPProducerConnectionRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def producerConnectionRecovery = AMQPTest.withCleanEndState((mySys: ActorSystem) ⇒ {

    //TODO is there a cleaner way to handle this implicit declaration?
    implicit val system = mySys
    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    try {
      val amqp = system.actorOf(Props[AMQPActor])
      val connectionFuture = (amqp ? ConnectionRequest(ConnectionParameters(initReconnectDelay = 50))) mapTo manifest[ActorRef]
      val connection = Await.result(connectionFuture, timeout.duration)

      val startedLatch = new TestLatch
      val restartingLatch = new TestLatch
      val restartedLatch = new TestLatch

      val producerCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒ {
            if (!startedLatch.isOpen) {
              startedLatch.open
            } else {
              restartedLatch.open
            }
          }
          case Restarting ⇒ restartingLatch.open

          case Stopped    ⇒ ()
        }
      }))

      val channelParameters = ChannelParameters(channelCallback = Some(producerCallback))
      val producerParameters = ProducerParameters(
        Some(ExchangeParameters("text_exchange")), channelParameters = Some(channelParameters))

      Await.result((connection ? ProducerRequest(producerParameters)) mapTo manifest[ActorRef], timeout.duration)

      Await.result(startedLatch, timeout.duration)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))

      Await.result(restartingLatch, timeout.duration)
      Await.result(restartedLatch, timeout.duration)
    } catch {
      case e: Exception ⇒ fail(e)
    } finally {
      system.shutdown
    }
  })
}
