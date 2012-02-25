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

class AMQPConsumerPrivateQueueTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = AMQPTest.withCleanEndState((mySys: ActorSystem) ⇒ {

    //TODO is there a cleaner way to handle this implicit declaration?
    implicit val system = mySys
    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    try {
      val amqp = system.actorOf(Props[AMQPActor])
      val connectionFuture = (amqp ? ConnectionRequest(ConnectionParameters(initReconnectDelay = 50))) mapTo manifest[ActorRef]
      val connection = Await.result(connectionFuture, timeout.duration)

      val countDown = new TestLatch(2)
      val payloadLatch = new TestLatch

      val channelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started    ⇒ countDown.countDown
          case Restarting ⇒ ()
          case Stopped    ⇒ ()
        }
      }))

      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      Await.result((connection ? ConsumerRequest(
        ConsumerParameters("my.private.routing.key", system.actorOf(Props(new Actor {
          def receive = { case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open }
        })), channelParameters = Some(channelParameters))))
        mapTo manifest[ActorRef], timeout.duration)

      val producer = Await.result((connection ? ProducerRequest(
        ProducerParameters(channelParameters = Some(channelParameters))))
        mapTo manifest[ActorRef], timeout.duration)

      Await.result(countDown, timeout.duration)

      producer ! Message("some_payload".getBytes, "my.private.routing.key")

      Await.result(payloadLatch, timeout.duration)
    } catch {
      case e: Exception ⇒ fail(e)
    } finally {
      system.shutdown
    }
  })
}