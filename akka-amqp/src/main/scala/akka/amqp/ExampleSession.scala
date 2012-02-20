/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.lang.String
import akka.amqp.AMQP._
import akka.amqp.AkkaAmqp.TestMessage
import akka.actor.{ Props, ActorSystem, Actor }

object ExampleSession {

  val system = ActorSystem.create("ExampleSession")

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

    printTopic("Happy hAkking :-)")

    // postStop everything the amqp tree except the main AMQP system
    // all connections/consumers/producers will be stopped
    AMQP.shutdownAll()
    system.shutdown()
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

    val exchangeParameters = ExchangeParameters("my_direct_exchange", Direct)

    AMQP.newConsumer(connection, ConsumerParameters("some.routing", system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒ println("@george_bush received message from: %s".format(new String(payload)))
      }
    })), None, Some(exchangeParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters))).
      getOrElse(throw new NoSuchElementException("Could not create producer"))
    producer ! Message("@jonas_boner: You sucked!!".getBytes, "some.routing")
  }

  def fanout = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_fanout_exchange", Fanout)

    AMQP.newConsumer(connection, ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒ println("@george_bush received message from: %s".format(new String(payload)))
      }
    })), None, Some(exchangeParameters)))

    AMQP.newConsumer(connection, ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒ println("@barack_obama received message from: %s".format(new String(payload)))
      }
    })), None, Some(exchangeParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters))).
      getOrElse(throw new NoSuchElementException("Could not create producer"))
    producer ! Message("@jonas_boner: I'm going surfing".getBytes, "")
  }

  def topic = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val exchangeParameters = ExchangeParameters("my_topic_exchange", Topic)

    AMQP.newConsumer(connection, ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒ println("@george_bush received message from: %s".format(new String(payload)))
      }
    })), None, Some(exchangeParameters)))

    AMQP.newConsumer(connection, ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒ println("@barack_obama received message from: %s".format(new String(payload)))
      }
    })), None, Some(exchangeParameters)))

    val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters))).
      getOrElse(throw new NoSuchElementException("Could not create producer"))
    producer ! Message("@jonas_boner: You still suck!!".getBytes, "@george_bush")
    producer ! Message("@jonas_boner: Yes I can!".getBytes, "@barack_obama")
  }

  def callback = {

    val channelCountdown = new CountDownLatch(2)

    val connectionCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Connected    ⇒ println("Connection callback: Connected!")
        case Reconnecting ⇒ () // not used, sent when connection fails and initiates a reconnect
        case Disconnected ⇒ println("Connection callback: Disconnected!")
      }
    }))

    val connection = AMQP.newConnection(new ConnectionParameters(connectionCallback = Some(connectionCallback)))

    val channelCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Started ⇒ {
          println("Channel callback: Started")
          channelCountdown.countDown
        }
        case Restarting ⇒ // not used, sent when channel or connection fails and initiates a restart
        case Stopped    ⇒ println("Channel callback: Stopped")
      }
    }))

    val exchangeParameters = ExchangeParameters("my_callback_exchange", Direct)
    val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

    AMQP.newConsumer(connection, ConsumerParameters("callback.routing", system.actorOf(Props(new Actor {
      def receive = {
        case _ ⇒ () // not used
      }
    })), None, Some(exchangeParameters), channelParameters = Some(channelParameters)))

    AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters))).
      getOrElse(throw new NoSuchElementException("Could not create producer"))

    // Wait until both channels (producer & consumer) are started before stopping the connection
    channelCountdown.await(2, TimeUnit.SECONDS)
    system.stop(connection)
  }

  def easyStringProducerConsumer = {
    val connection = AMQP.newConnection()

    val exchangeName = "easy.string"

    // listen by default to:
    // exchange = optional exchangeName
    // routingKey = provided routingKey or <exchangeName>.request
    // queueName = <routingKey>.in
    AMQP.newStringConsumer(connection, message ⇒ println("Received message: " + message), Some(exchangeName))

    // send by default to:
    // exchange = exchangeName
    // routingKey = <exchange>.request
    val producer = AMQP.newStringProducer(connection, Some(exchangeName))

    producer.send("This is easy!")
  }

  def easyProtobufProducerConsumer = {
    val connection = AMQP.newConnection()

    val exchangeName = "easy.protobuf"

    def protobufMessageHandler(message: TestMessage) = {
      println("Received " + message)
    }

    AMQP.newProtobufConsumer(connection, protobufMessageHandler _, Some(exchangeName))

    val producerClient = AMQP.newProtobufProducer[TestMessage](connection, Some(exchangeName))
    producerClient.send(TestMessage.newBuilder.setMessage("akka-amqp rocks!").build)
  }
}
