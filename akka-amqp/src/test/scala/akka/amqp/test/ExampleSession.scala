package akka.amqp.test

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.lang.String
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import akka.amqp._
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import akka.testkit.TestLatch
import org.junit.Test
import akka.util.{ NonFatal, Timeout }
import akka.dispatch.{ Future, Await }
import akka.event.Logging
import java.nio.charset.Charset

object ExampleSession {

  implicit val system = ActorSystem.create("ExampleSession", ConfigFactory.load.getConfig("example"))
  val amqp = system.actorOf(Props(new AMQPActor))
  val log = Logging(system, "ExampleSession")

  val settings = Settings(system)
  implicit val timeout = Timeout(settings.Timeout)
  val utf8Charset = Charset.forName("UTF-8")

  // defaults to amqp://guest:guest@localhost:5672/
  val connection = amqp ? ConnectionRequest(ConnectionParameters()) mapTo manifest[ActorRef]

  def main(args: Array[String]) = {

    try {
      printTopic("DIRECT")
      direct

      printTopic("FANOUT")
      fanout

      printTopic("TOPIC")
      topic

      printTopic("CALLBACK")
      callback

      printTopic("Happy hAkking :-)")

      // postStop everything the amqp tree except the main AMQP system
      // all connections/consumers/producers will be stopped
    } catch {
      case e: Exception ⇒ e.printStackTrace()
    } finally {
      system.shutdown
    }
  }

  def printTopic(topic: String) {

    log.info("")
    log.info("==== " + topic + " ===")
    log.info("")
  }

  def direct = {

    val msgProcessed = new CountDownLatch(1)
    val consumerStartedLatch = new TestLatch
    val producerStartedLatch = new TestLatch

    val consumerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumerStartedLatch)))
    val producerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(producerStartedLatch)))

    val exchangeParameters = ExchangeParameters("my_direct_exchange", Direct)

    val consumer: Future[ActorRef] = for {
      conn ← connection
      cr ← (conn ? ConsumerRequest(
        ConsumerParameters("some.routing", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown
            }
          }
        })), None, Some(exchangeParameters),
          channelParameters = Some(consumerChannelParameters)))) mapTo manifest[ActorRef]
    } yield cr

    val producer: Future[ActorRef] = for {
      conn ← connection
      pr ← conn ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters),
          channelParameters = Some(producerChannelParameters))) mapTo manifest[ActorRef]
    } yield pr

    Await.result(consumerStartedLatch, timeout.duration)
    Await.result(producerStartedLatch, timeout.duration)

    producer map (_ ! Message("@jonas_boner: You sucked!!".getBytes(utf8Charset).toSeq, "some.routing"))

    // just to make sure we receive before moving to next test
    if (!msgProcessed.await(timeout.duration.toMillis, TimeUnit.SECONDS)) throw new Exception("didn't get all messages.")
  }

  def fanout = {

    val msgProcessed = new CountDownLatch(2)
    val consumer1StartedLatch = new TestLatch
    val consumer2StartedLatch = new TestLatch
    val producerStartedLatch = new TestLatch

    val consumer1ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer1StartedLatch)))
    val consumer2ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer2StartedLatch)))
    val producerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(producerStartedLatch)))

    val exchangeParameters = ExchangeParameters("my_fanout_exchange", Fanout)

    val consumer1: Future[ActorRef] = connection flatMap (_ ? ConsumerRequest(
      ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters),
        channelParameters = Some(consumer1ChannelParameters)))) mapTo manifest[ActorRef]

    val consumer2: Future[ActorRef] = connection flatMap (_ ? ConsumerRequest(
      ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            log.info("@barack_obama received message from: {}", new String(payload.toArray, utf8Charset.name()))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters),
        channelParameters = Some(consumer2ChannelParameters)))) mapTo manifest[ActorRef]

    val producer = connection flatMap (_ ? ProducerRequest(
      ProducerParameters(Some(exchangeParameters),
        channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef]

    Await.result(consumer1StartedLatch, timeout.duration)
    Await.result(consumer2StartedLatch, timeout.duration)
    Await.result(producerStartedLatch, timeout.duration)

    producer map (_ ! Message("@jonas_boner: I'm going surfing".getBytes(utf8Charset).toSeq, ""))
    if (!msgProcessed.await(5, TimeUnit.SECONDS)) throw new Exception("didn't get all messages.")
  }

  def topic = {

    val msgProcessed = new CountDownLatch(2)
    val consumer1StartedLatch = new TestLatch
    val consumer2StartedLatch = new TestLatch
    val producerStartedLatch = new TestLatch

    val consumer1ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer1StartedLatch)))
    val consumer2ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer2StartedLatch)))
    val producerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(producerStartedLatch)))

    val exchangeParameters = ExchangeParameters("my_topic_exchange", Topic)

    val consumer1: Future[ActorRef] = connection flatMap (_ ? ConsumerRequest(
      ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters),
        channelParameters = Some(consumer1ChannelParameters)))) mapTo manifest[ActorRef]

    val consumer2: Future[ActorRef] = connection flatMap (_ ? ConsumerRequest(
      ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            log.info("@barack_obama received message from: {}", new String(payload.toArray, utf8Charset.name()))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters),
        channelParameters = Some(consumer2ChannelParameters)))) mapTo manifest[ActorRef]

    val producer = connection flatMap (_ ? ProducerRequest(
      ProducerParameters(Some(exchangeParameters),
        channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef]

    Await.result(consumer1StartedLatch, timeout.duration)
    Await.result(consumer2StartedLatch, timeout.duration)
    Await.result(producerStartedLatch, timeout.duration)

    producer map (_ ! Message("@jonas_boner: You still suck!!".getBytes(utf8Charset).toSeq, "@george_bush"))
    producer map (_ ! Message("@jonas_boner: Yes I can!".getBytes(utf8Charset).toSeq, "@barack_obama"))

    if (!msgProcessed.await(5, TimeUnit.SECONDS)) throw new Exception("didn't get all messages.")
  }

  def callback = {

    val channelCountdown = new CountDownLatch(2)
    val connectedLatch = new TestLatch

    val connectionCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Connected ⇒ {
          log.info("Connection callback: Connected!")
          connectedLatch.open
        }
        case Reconnecting ⇒ () // not used, sent when connection fails and initiates a reconnect
        case Disconnected ⇒ log.info("Connection callback: Disconnected!")
      }
    }))

    val callbackConnection = amqp ? ConnectionRequest(
      ConnectionParameters(connectionCallback = Some(connectionCallback))) mapTo manifest[ActorRef]

    val channelCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Started ⇒ {
          log.info("Channel callback: Started")
          channelCountdown.countDown
        }
        case Restarting ⇒ // not used, sent when channel or connection fails and initiates a restart
        case Stopped    ⇒ log.info("Channel callback: Stopped")
      }
    }))

    val exchangeParameters = ExchangeParameters("my_callback_exchange", Direct)
    val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

    Await.result(connectedLatch, timeout.duration)

    callbackConnection flatMap (_ ? ConsumerRequest(
      ConsumerParameters("callback.routing", system.actorOf(Props(new Actor {
        def receive = {
          case _ ⇒ () // not used
        }
      })), None, Some(exchangeParameters),
        channelParameters = Some(channelParameters))) mapTo manifest[ActorRef])

    callbackConnection flatMap (_ ? ProducerRequest(
      ProducerParameters(Some(exchangeParameters),
        channelParameters = Some(channelParameters))) mapTo manifest[ActorRef])

    // Wait until both channels (producer & consumer) are started before stopping the connection
    if (!channelCountdown.await(5, TimeUnit.SECONDS)) throw new Exception("didn't start all channels.")
  }

  /**
   * use this to confirm that the consumer/producer channels have been started prior to sending messages
   */

  def channelCallback(startedLatch: TestLatch) = system.actorOf(Props(new Actor {
    def receive = {
      case Started ⇒ startedLatch.open
      case _       ⇒ ()
    }
  }))
}
