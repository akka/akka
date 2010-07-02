/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.{Actor, ActorRegistry}
import Actor._
import se.scalablesolutions.akka.amqp.AMQP.{ConnectionParameters, ConsumerParameters, ChannelParameters, ProducerParameters}
import java.util.concurrent.{CountDownLatch, TimeUnit}

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

    ActorRegistry.shutdownAll
    System.exit(0)
  }

  def direct = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val channelParameters = ChannelParameters("my_direct_exchange", ExchangeType.Direct)

    val consumer = AMQP.newConsumer(connection, ConsumerParameters(channelParameters, "some.routing", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }))

    val producer = AMQP.newProducer(connection, ProducerParameters(channelParameters))
    producer ! Message("@jonas_boner: You sucked!!".getBytes, "some.routing")
  }

  def fanout = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val channelParameters = ChannelParameters("my_fanout_exchange", ExchangeType.Fanout)

    val bushConsumer = AMQP.newConsumer(connection, ConsumerParameters(channelParameters, "@george_bush", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }))

    val obamaConsumer = AMQP.newConsumer(connection, ConsumerParameters(channelParameters, "@barack_obama", actor {
      case Delivery(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", new String(payload))
    }))

    val producer = AMQP.newProducer(connection, ProducerParameters(channelParameters))
    producer ! Message("@jonas_boner: I'm going surfing".getBytes, "")
  }

  def topic = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection()

    val channelParameters = ChannelParameters("my_topic_exchange", ExchangeType.Topic)

    val bushConsumer = AMQP.newConsumer(connection, ConsumerParameters(channelParameters, "@george_bush", actor {
      case Delivery(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload))
    }))

    val obamaConsumer = AMQP.newConsumer(connection, ConsumerParameters(channelParameters, "@barack_obama", actor {
      case Delivery(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", new String(payload))
    }))

    val producer = AMQP.newProducer(connection, ProducerParameters(channelParameters))
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
    val channelParameters = ChannelParameters("my_direct_exchange", ExchangeType.Direct, channelCallback = Some(channelCallback))

    val consumer = AMQP.newConsumer(connection, ConsumerParameters(channelParameters, "callback.routing", actor {
      case _ => () // not used
    }))

    val producer = AMQP.newProducer(connection, ProducerParameters(channelParameters))

    // Wait until both channels (producer & consumer) are started before stopping the connection
    channelCountdown.await(2, TimeUnit.SECONDS)
    connection.stop
  }
}
