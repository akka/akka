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
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.amqp.AMQP._

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
      val channelParameters = ChannelParameters(channelCallback
              = Some(channelCallback))

      val serverFromBinary = new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]) = new String(bytes)
      }
      val serverToBinary = new ToBinary[Int] {
        def toBinary(t: Int) = Array(t.toByte)
      }
      val rpcServerSerializer = new RpcServerSerializer[String, Int](serverFromBinary, serverToBinary)
      val rpcServer = AMQP.newRpcServer[String,Int](connection, exchangeParameters, "rpc.routing", rpcServerSerializer, {
        case "some_payload" => 3
        case _ => error("unknown request")
      }, channelParameters = Some(channelParameters))

      val clientToBinary = new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      }
      val clientFromBinary = new FromBinary[Int] {
        def fromBinary(bytes: Array[Byte]) = bytes.head.toInt
      }
      val rpcClientSerializer = new RpcClientSerializer[String, Int](clientToBinary, clientFromBinary)
      val rpcClient = AMQP.newRpcClient[String,Int](connection, exchangeParameters, "rpc.routing", rpcClientSerializer,
        channelParameters = Some(channelParameters))

      countDown.await(2, TimeUnit.SECONDS) must be (true)
      val response = rpcClient !! "some_payload"
      response must be (Some(3))
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