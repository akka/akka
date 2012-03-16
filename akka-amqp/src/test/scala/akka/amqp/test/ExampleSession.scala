package akka.amqp.test

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import java.lang.String
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import akka.amqp._
import akka.testkit.TestLatch
import akka.util.Timeout
import akka.event.Logging
import java.nio.charset.Charset
import akka.dispatch.{ Promise, Future, Await }

object ExampleSession {

  implicit val system = ActorSystem.create("ExampleSession", ConfigFactory.load.getConfig("example"))
  val log = Logging(system, "ExampleSession")

  val settings = AMQP(system)
  implicit val timeout = Timeout(settings.Timeout)
  val utf8Charset = Charset.forName("UTF-8")

  def main(args: Array[String]) = {
    system.actorOf(Props[MyActor])
  }

  class MyActor extends Actor {
    def receive = {
      case _ ⇒ ()
    }

    try {

      direct
      fanout
      topic
      callback

    } catch {
      case e: Exception ⇒ e.printStackTrace()
    } finally {
      system.scheduler.scheduleOnce(timeout.duration * 4)(system.shutdown)
      printTopic("Happy hAkking :-)")
    }

    def printTopic(topic: String) {

      log.info("")
      log.info("==== " + topic + " ===")
      log.info("")
    }

    def direct = {

      // defaults to amqp://guest:guest@localhost:5672/
      //val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters())), "direct")
      val connection = AMQP.newConnection(context, ConnectionParameters(), Option("direct"))
      val msgProcessed = new TestLatch

      val exchangeParameters = ExchangeParameters("my_direct_exchange", Direct)

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("some.routing", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (c ← cf; p ← pf) {
        p ! Message("@jxstanford: You sucked!!".getBytes(utf8Charset).toSeq, "some.routing")

      }
      Await.result(msgProcessed, timeout.duration)

      system stop connection
    }

    def fanout = {

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters())), "fanout")

      val msgProcessed = new TestLatch(2)

      val exchangeParameters = ExchangeParameters("my_fanout_exchange", Fanout)

      val cf1: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val cf2: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@barack_obama received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (c1 ← cf1; c2 ← cf2; p ← pf) {
        p ! Message("@jxstanford: I'm going surfing".getBytes(utf8Charset).toSeq, "")

      }
      Await.result(msgProcessed, timeout.duration)
      system stop connection
    }

    def topic = {

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters())), "topic")

      val msgProcessed = new TestLatch(2)

      val exchangeParameters = ExchangeParameters("my_topic_exchange", Topic)

      val cf1: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@george_bush received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val cf2: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒ {
              log.info("@barack_obama received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            }
          }
        })), None, Some(exchangeParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (c1 ← cf1; c2 ← cf2; p ← pf) {
        p ! Message("@jxstanford: You still suck!!".getBytes(utf8Charset).toSeq, "@george_bush")
        p ! Message("@jxstanford: Yes I can!".getBytes(utf8Charset).toSeq, "@barack_obama")
      }
      Await.result(msgProcessed, timeout.duration)
      system stop connection
    }

    def callback = {

      val msgProcessed = new TestLatch

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters())), "callback")

      var startedConsumer = Promise.apply[AMQPMessage]()
      var startedProducer = Promise.apply[AMQPMessage]()

      val consumerChannelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒
            log.info("Channel callback: Started")
            startedConsumer.success(Started)
          case Restarting ⇒ startedConsumer = Promise.apply[AMQPMessage]()
          case Stopped ⇒
            log.info("Channel callback: Stopped")
            startedConsumer = Promise.apply[AMQPMessage]()
        }
      }))

      val producerChannelCallback = system.actorOf(Props(new Actor {
        def receive = {
          case Started ⇒
            log.info("Channel callback: Started")
            startedProducer.success(Started)
          case Restarting ⇒ startedProducer = Promise.apply[AMQPMessage]()
          case Stopped ⇒
            log.info("Channel callback: Stopped")
            startedProducer = Promise.apply[AMQPMessage]()
        }
      }))

      val exchangeParameters = ExchangeParameters("my_callback_exchange", Direct)
      val consumerChannelParameters = ChannelParameters(channelCallback = Some(consumerChannelCallback))
      val producerChannelParameters = ChannelParameters(channelCallback = Some(producerChannelCallback))

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("callback.routing", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, _, _, _, _) ⇒
              log.info("@barack_obama received message from: {}", new String(payload.toArray, utf8Charset.name()))
              msgProcessed.countDown()
            case _ ⇒ () // not used
          }
        })), None, Some(exchangeParameters),
          channelParameters = Some(consumerChannelParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters),
          channelParameters = Some(producerChannelParameters))) mapTo manifest[ActorRef]

      for (c ← cf; sc ← startedConsumer; p ← pf; sp ← startedProducer) {
        log.debug("********* About to produce a message...")
        p ! Message("@jxstanford: I am productive!!".getBytes(utf8Charset).toSeq, "callback.routing")
      }

      Await.result(msgProcessed, timeout.duration)
      system stop connection
    }
  }

}
