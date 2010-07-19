/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{Actor, ActorRegistry}
import Actor._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.amqp.AMQP._
import java.lang.String

object ExampleSession {

  def main(args: Array[String]) = {
    println("==== DIRECT ===")
    direct

    TimeUnit.SECONDS.sleep(2)

    println("==== FANOUT ===")
    fanout

    TimeUnit.SECONDS.sleep(2)

    println("==== TOPIC  ===")
    topic

    TimeUnit.SECONDS.sleep(2)

    println("==== CALLBACK  ===")
    callback

    TimeUnit.SECONDS.sleep(2)

    println("==== RPC  ===")
    rpc

    TimeUnit.SECONDS.sleep(2)

    ActorRegistry.shutdownAll
    System.exit(0)
  }

  def direct = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_direct_exchange", ExchangeType.Direct)

    val consumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "some.routing", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }))

    val producer = AMQP.newProducer(connection, ProducerParameters(exchangeParameters))
    producer ! Message("@jonas_boner: You sucked!!".getBytes, "some.routing")
  }

  def fanout = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_fanout_exchange", ExchangeType.Fanout)

    val bushConsumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "@george_bush", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }))

    val obamaConsumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "@barack_obama", actor {
      case Delivery(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", new String(payload))
    }))

    val producer = AMQP.newProducer(connection, ProducerParameters(exchangeParameters))
    producer ! Message("@jonas_boner: I'm going surfing".getBytes, "")
  }

  def topic = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_topic_exchange", ExchangeType.Topic)

    val bushConsumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "@george_bush", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }))

    val obamaConsumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "@barack_obama", actor {
      case Delivery(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", new String(payload))
    }))

    val producer = AMQP.newProducer(connection, ProducerParameters(exchangeParameters))
    producer ! Message("@jonas_boner: You still suck!!".getBytes, "@george_bush")
    producer ! Message("@jonas_boner: Yes I can!".getBytes, "@barack_obama")
  }

  def callback = {

    val channelCountdown = new CountDownLatch(2)

    val connectionCallback = actor {
      case Connected => log.info("Connection callback: Connected!")
      case Reconnecting => () // not used, sent when connection fails and initiates a reconnect
      case Disconnected => log.info("Connection callback: Disconnected!")
    }
    val connection = AMQP.newConnection(new ConnectionParameters(connectionCallback = Some(connectionCallback)))

    val channelCallback = actor {
      case Started => {
        log.info("Channel callback: Started")
        channelCountdown.countDown
      }
      case Restarting => // not used, sent when channel or connection fails and initiates a restart
      case Stopped => log.info("Channel callback: Stopped")
    }
    val exchangeParameters = ExchangeParameters("my_direct_exchange", ExchangeType.Direct)
    val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

    val consumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "callback.routing", actor {
      case _ => () // not used
    }, channelParameters = Some(channelParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(exchangeParameters))

    // Wait until both channels (producer & consumer) are started before stopping the connection
    channelCountdown.await(2, TimeUnit.SECONDS)
    connection.stop
  }

  def rpc = {

    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_rpc_exchange", ExchangeType.Topic)

    /** Server */
    val serverFromBinary = new FromBinary[String] {
      def fromBinary(bytes: Array[Byte]) = new String(bytes)
    }
    val serverToBinary = new ToBinary[Int] {
      def toBinary(t: Int) = Array(t.toByte)
    }
    val rpcServerSerializer = new RpcServerSerializer[String, Int](serverFromBinary, serverToBinary)

    val rpcServer = AMQP.newRpcServer[String,Int](connection, exchangeParameters, "rpc.in.key", rpcServerSerializer, {
      case "rpc_request" => 3
      case _ => error("unknown request")
    })


    /** Client */
    val clientToBinary = new ToBinary[String] {
      def toBinary(t: String) = t.getBytes
    }
    val clientFromBinary = new FromBinary[Int] {
      def fromBinary(bytes: Array[Byte]) = bytes.head.toInt
    }
    val rpcClientSerializer = new RpcClientSerializer[String, Int](clientToBinary, clientFromBinary)

    val rpcClient = AMQP.newRpcClient[String,Int](connection, exchangeParameters, "rpc.in.key", rpcClientSerializer)

    val response = (rpcClient !! "rpc_request")
    log.info("Response: " + response)
  }
}
