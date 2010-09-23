/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import rpc.RPC
import rpc.RPC.{RpcClientSerializer, RpcServerSerializer}
import se.scalablesolutions.akka.actor.{Actor, ActorRegistry}
import Actor._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.lang.String
import se.scalablesolutions.akka.amqp.AMQP._
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol.AddressProtocol

object ExampleSession {

  def main(args: Array[String]) = {

    printTopic("DIRECT")
    direct

    printTopic("FANOUT")
    fanout

    printTopic("TOPIC")
    topic

    printTopic("CALLBACK")
    callback

    printTopic("EASY STRING PRODUCER AND CONSUMER")
    easyStringProducerConsumer

    printTopic("EASY PROTOBUF PRODUCER AND CONSUMER")
    easyProtobufProducerConsumer

    printTopic("RPC")
    rpc

    printTopic("EASY STRING RPC")
    easyStringRpc

    printTopic("EASY PROTOBUF RPC")
    easyProtobufRpc

    printTopic("Happy hAkking :-)")

    // postStop everything the amqp tree except the main AMQP supervisor
    // all connections/consumers/producers will be stopped
    AMQP.shutdownAll

    ActorRegistry.shutdownAll
    System.exit(0)
  }

  def printTopic(topic: String) {

    println("")
    println("==== " + topic + " ===")
    println("")
    TimeUnit.SECONDS.sleep(2)
  }

  def direct = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_direct_exchange", ExchangeType.Direct)

    val consumer = AMQP.newConsumer(connection, ConsumerParameters("some.routing", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }, None, Some(exchangeParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters)))
    producer ! Message("@jonas_boner: You sucked!!".getBytes, "some.routing")
  }

  def fanout = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_fanout_exchange", ExchangeType.Fanout)

    val bushConsumer = AMQP.newConsumer(connection, ConsumerParameters("@george_bush", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }, None, Some(exchangeParameters)))

    val obamaConsumer = AMQP.newConsumer(connection, ConsumerParameters("@barack_obama", actor {
      case Delivery(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", new String(payload))
    }, None, Some(exchangeParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters)))
    producer ! Message("@jonas_boner: I'm going surfing".getBytes, "")
  }

  def topic = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_topic_exchange", ExchangeType.Topic)

    val bushConsumer = AMQP.newConsumer(connection, ConsumerParameters("@george_bush", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }, None, Some(exchangeParameters)))

    val obamaConsumer = AMQP.newConsumer(connection, ConsumerParameters("@barack_obama", actor {
      case Delivery(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", new String(payload))
    }, None, Some(exchangeParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters)))
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
    val exchangeParameters = ExchangeParameters("my_callback_exchange", ExchangeType.Direct)
    val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

    val consumer = AMQP.newConsumer(connection, ConsumerParameters("callback.routing", actor {
      case _ => () // not used
    }, None, Some(exchangeParameters), channelParameters = Some(channelParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters)))

    // Wait until both channels (producer & consumer) are started before stopping the connection
    channelCountdown.await(2, TimeUnit.SECONDS)
    connection.stop
  }

  def easyStringProducerConsumer = {
    val connection = AMQP.newConnection()

    val exchangeName = "easy.string"

    // listen by default to:
    // exchange = optional exchangeName
    // routingKey = provided routingKey or <exchangeName>.request
    // queueName = <routingKey>.in
    AMQP.newStringConsumer(connection, message => println("Received message: "+message), Some(exchangeName))

    // send by default to:
    // exchange = exchangeName
    // routingKey = <exchange>.request
    val producer = AMQP.newStringProducer(connection, exchangeName)

    producer.send("This shit is easy!")
  }

  def easyProtobufProducerConsumer = {
    val connection = AMQP.newConnection()

    val exchangeName = "easy.protobuf"

    def protobufMessageHandler(message: AddressProtocol) = {
      log.info("Received "+message)
    }

    AMQP.newProtobufConsumer(connection, protobufMessageHandler, Some(exchangeName))

    val producerClient = AMQP.newProtobufProducer[AddressProtocol](connection, Some(exchangeName))
    producerClient.send(AddressProtocol.newBuilder.setHostname("akkarocks.com").setPort(1234).build)
  }

  def rpc = {

    val connection = AMQP.newConnection()

    val exchangeName = "my_rpc_exchange"

    /** Server */
    val serverFromBinary = new FromBinary[String] {
      def fromBinary(bytes: Array[Byte]) = new String(bytes)
    }
    val serverToBinary = new ToBinary[Int] {
      def toBinary(t: Int) = Array(t.toByte)
    }
    val rpcServerSerializer = new RpcServerSerializer[String, Int](serverFromBinary, serverToBinary)

    def requestHandler(request: String) = 3

    val rpcServer = RPC.newRpcServer[String,Int](connection, exchangeName, "rpc.in.key", rpcServerSerializer,
      requestHandler, queueName = Some("rpc.in.key.queue"))


    /** Client */
    val clientToBinary = new ToBinary[String] {
      def toBinary(t: String) = t.getBytes
    }
    val clientFromBinary = new FromBinary[Int] {
      def fromBinary(bytes: Array[Byte]) = bytes.head.toInt
    }
    val rpcClientSerializer = new RpcClientSerializer[String, Int](clientToBinary, clientFromBinary)

    val rpcClient = RPC.newRpcClient[String,Int](connection, exchangeName, "rpc.in.key", rpcClientSerializer)

    val response = (rpcClient !! "rpc_request")
    log.info("Response: " + response)
  }

  def easyStringRpc = {

    val connection = AMQP.newConnection()

    val exchangeName = "easy.stringrpc"

    // listen by default to:
    // exchange = exchangeName
    // routingKey = <exchange>.request
    // queueName = <routingKey>.in
    RPC.newStringRpcServer(connection, exchangeName, request => {
      log.info("Got request: "+request)
      "Response to: '"+request+"'"
    })

    // send by default to:
    // exchange = exchangeName
    // routingKey = <exchange>.request
    val stringRpcClient = RPC.newStringRpcClient(connection, exchangeName)

    val response = stringRpcClient.call("AMQP Rocks!")
    log.info("Got response: "+response)

    stringRpcClient.callAsync("AMQP is dead easy") {
      case response => log.info("This is handled async: "+response)
    }
  }

  def easyProtobufRpc = {

    val connection = AMQP.newConnection()

    val exchangeName = "easy.protobuf.rpc"

    def protobufRequestHandler(request: AddressProtocol): AddressProtocol = {
      AddressProtocol.newBuilder.setHostname(request.getHostname.reverse).setPort(request.getPort).build
    }

    RPC.newProtobufRpcServer(connection, exchangeName, protobufRequestHandler)

    val protobufRpcClient = RPC.newProtobufRpcClient[AddressProtocol, AddressProtocol](connection, exchangeName)

    val response = protobufRpcClient.call(AddressProtocol.newBuilder.setHostname("localhost").setPort(4321).build)

    log.info("Got response: "+response)
  }
}
