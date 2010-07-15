/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.amqp._
import se.scalablesolutions.akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.{CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.amqp.AMQP.{ExchangeParameters, ChannelParameters}
import se.scalablesolutions.akka.serialization.Serializer

class AMQPRpcClientServerTest extends JUnitSuite with MustMatchers with Logging {

  @Test
  def consumerMessage = if (AMQPTest.enabled) {
    val connection = AMQP.newConnection()
    try {

      val countDown = new CountDownLatch(3)
      val channelCallback = actor {
        case Started => countDown.countDown
        case Restarting => ()
        case Stopped => ()
      }

      val exchangeParameters = ExchangeParameters("text_topic_exchange", ExchangeType.Topic)
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))
      val stringSerializer = new Serializer {
        def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]) = new String(bytes)
        def toBinary(obj: AnyRef) = obj.asInstanceOf[String].getBytes
      }

      val rpcServer = AMQP.newRpcServer(connection, exchangeParameters, "rpc.routing", stringSerializer, stringSerializer, {
        case "some_payload" => "some_result"
        case _ => error("Unhandled message")
      }, channelParameters = Some(channelParameters))

      val rpcClient = AMQP.newRpcClient(connection, exchangeParameters, "rpc.routing", stringSerializer, stringSerializer
        , channelParameters = Some(channelParameters))

      countDown.await(2, TimeUnit.SECONDS) must be (true)
      val response = rpcClient !! "some_payload"
      response must be (Some("some_result"))
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