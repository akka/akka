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
import akka.testkit.TestLatch
import akka.util.Timeout
import akka.dispatch.{ Future, Await }
import akka.event.Logging
import java.nio.charset.Charset
import akka.util.duration._

object ExampleSession {

  implicit val system = ActorSystem.create("ExampleSession", ConfigFactory.load.getConfig("example"))
  val amqp = system.actorOf(Props(new AMQPActor))
  val log = Logging(system, "ExampleSession")

  val settings = Settings(system)
  implicit val timeout = Timeout(settings.Timeout)
  val utf8Charset = Charset.forName("UTF-8")

  def main(args: Array[String]) = {

    try {

      direct
      fanout
      topic
      callback

      // postStop everything the amqp tree except the main AMQP system
      // all connections/consumers/producers will be stopped
    } catch {
      case e: Exception ⇒ e.printStackTrace()
    } finally {
      system.scheduler.scheduleOnce(timeout.duration * 4)(system.shutdown)
      printTopic("Happy hAkking :-)")
    }
  }

  def printTopic(topic: String) {

    log.info("")
    log.info("==== " + topic + " ===")
    log.info("")
  }

  def direct = {

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = amqp ? ConnectionRequest(ConnectionParameters()) mapTo manifest[ActorRef]

    val msgProcessed = new TestLatch

    val exchangeParameters = ExchangeParameters("my_direct_exchange", Direct)

    for (conn ← connection) {

      val cf: Future[ActorRef] = conn ? ConsumerRequest(
        ConsumerParameters("some.routing", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = conn ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (c ← cf; p ← pf) {
        p ! Message("@jxstanford: You sucked!!".getBytes(utf8Charset).toSeq, "some.routing")

      }
      Await.result(msgProcessed, timeout.duration)
      system stop conn
    }
  }

  def fanout = {

    val connection = amqp ? ConnectionRequest(ConnectionParameters()) mapTo manifest[ActorRef]

    val msgProcessed = new TestLatch(2)

    val exchangeParameters = ExchangeParameters("my_fanout_exchange", Fanout)

    for (conn ← connection) {

      val cf1: Future[ActorRef] = conn ? ConsumerRequest(
        ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val cf2: Future[ActorRef] = conn ? ConsumerRequest(
        ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@barack_obama received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = conn ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (c1 ← cf1; c2 ← cf2; p ← pf) {
        p ! Message("@jxstanford: I'm going surfing".getBytes(utf8Charset).toSeq, "")

      }
      Await.result(msgProcessed, timeout.duration)
      system stop conn
    }
  }

  def topic = {

    val connection = amqp ? ConnectionRequest(ConnectionParameters()) mapTo manifest[ActorRef]

    val msgProcessed = new TestLatch(2)

    val exchangeParameters = ExchangeParameters("my_topic_exchange", Topic)

    for (conn ← connection) {

      val cf1: Future[ActorRef] = conn ? ConsumerRequest(
        ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val cf2: Future[ActorRef] = conn ? ConsumerRequest(
        ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@barack_obama received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = conn ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (c1 ← cf1; c2 ← cf2; p ← pf) {
        p ! Message("@jxstanford: You still suck!!".getBytes(utf8Charset).toSeq, "@george_bush")
        p ! Message("@jxstanford: Yes I can!".getBytes(utf8Charset).toSeq, "@barack_obama")
      }
      Await.result(msgProcessed, timeout.duration)
      system stop conn
    }
  }

  def callback = {

    val channelCountdown = new TestLatch(2)

    val connection = amqp ? ConnectionRequest(ConnectionParameters()) mapTo manifest[ActorRef]

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

    for (conn ← connection) {

      val cf: Future[ActorRef] = conn ? ConsumerRequest(
        ConsumerParameters("callback.routing", system.actorOf(Props(new Actor {
          def receive = {
            case _ ⇒ () // not used
          }
        })), None, Some(exchangeParameters),
          channelParameters = Some(channelParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = conn ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters),
          channelParameters = Some(channelParameters))) mapTo manifest[ActorRef]

      Await.result(channelCountdown, timeout.duration)
      system stop conn
    }
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
