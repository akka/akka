/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.amqp._
import org.multiverse.api.latches.StandardLatch
import se.scalablesolutions.akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.{CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.amqp.AMQP.{ExchangeParameters, ChannelParameters}

class AMQPRpcClientServerTest extends JUnitSuite with MustMatchers with Logging {

  @Test
  def consumerMessage = {
    val connection = AMQP.newConnection()
    try {

      val countDown = new CountDownLatch(4)
      val channelCallback = actor {
        case Started => countDown.countDown
        case Restarting => ()
        case Stopped => ()
      }

      val exchangeParameters = ExchangeParameters("text_exchange",ExchangeType.Topic)
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      def requestHandler(request: Array[Byte]): Array[Byte] = {
        "someresult".getBytes
      }
      
      val rpcServer = AMQP.newRpcServer(connection, exchangeParameters, "rpc.routing", requestHandler, channelParameters = Some(channelParameters))

      val payloadLatch = new StandardLatch
      val rpcClient = AMQP.newRpcClient(connection, exchangeParameters, "rpc.routing", actor {
        case Delivery(payload, _, _, _, _) => payloadLatch.open
      }, channelParameters = Some(channelParameters))

      countDown.await(2, TimeUnit.SECONDS) must be (true)
      rpcClient ! "some_payload".getBytes
      payloadLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
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