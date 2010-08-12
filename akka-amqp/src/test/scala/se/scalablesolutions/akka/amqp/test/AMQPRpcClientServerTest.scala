package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import se.scalablesolutions.akka.amqp._
import rpc.RPC
import rpc.RPC.{RpcClientSerializer, RpcServerSerializer}
import se.scalablesolutions.akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.{CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.amqp.AMQP._
import org.scalatest.junit.JUnitSuite
import org.junit.Test

class AMQPRpcClientServerTest extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = if (AMQPTest.enabled) AMQPTest.withCleanEndState {
    
    val connection = AMQP.newConnection()

    val countDown = new CountDownLatch(3)
    val channelCallback = actor {
      case Started => countDown.countDown
      case Restarting => ()
      case Stopped => ()
    }

    val exchangeParameters = ExchangeParameters("text_topic_exchange", ExchangeType.Topic)
    val channelParameters = ChannelParameters(channelCallback
            = Some(channelCallback))

    val rpcServerSerializer = new RpcServerSerializer[String, Int](
      new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]) = new String(bytes)
      }, new ToBinary[Int] {
        def toBinary(t: Int) = Array(t.toByte)
      })

    def requestHandler(request: String) = 3

    val rpcServer = RPC.newRpcServer[String, Int](connection, exchangeParameters, "rpc.routing", rpcServerSerializer,
      requestHandler, channelParameters = Some(channelParameters))

    val rpcClientSerializer = new RpcClientSerializer[String, Int](
      new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      }, new FromBinary[Int] {
        def fromBinary(bytes: Array[Byte]) = bytes.head.toInt
      })

    val rpcClient = RPC.newRpcClient[String, Int](connection, exchangeParameters, "rpc.routing", rpcClientSerializer,
      channelParameters = Some(channelParameters))

    countDown.await(2, TimeUnit.SECONDS) must be(true)
    val response = rpcClient !! "some_payload"
    response must be(Some(3))
  }
}
