/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import akka.actor._
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import util.Random
import akka.dispatch.Await
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import akka.amqp._
import akka.event.Logging
import akka.testkit.TestLatch
import akka.util.duration._
import java.nio.charset.Charset
import com.eaio.uuid.UUID

object LoadBalancingDemo {

  implicit val system = ActorSystem.create("LoadBalancingDemo", ConfigFactory.load.getConfig("example"))
  val utf8Charset = Charset.forName("UTF-8")

  def main(args: Array[String]) = {
    system.actorOf(Props[MyActor])
  }

  class MyActor extends Actor {
    def receive = {
      case _ ⇒ ()
    }

    case class ConnectionLatches(connected: TestLatch = new TestLatch, reconnecting: TestLatch = new TestLatch,
                                 reconnected: TestLatch = new TestLatch, disconnected: TestLatch = new TestLatch)

    case class ChannelLatches(started: TestLatch = new TestLatch, restarting: TestLatch = new TestLatch,
                              restarted: TestLatch = new TestLatch, stopped: TestLatch = new TestLatch)

    class ConnectionCallbackActor(latches: ConnectionLatches) extends Actor {

      def receive = {
        case Connected ⇒
          if (!latches.connected.isOpen) {
            latches.connected.open
          } else {
            latches.reconnected.open
          }
        case Reconnecting ⇒ latches.reconnecting.open
        case Disconnected ⇒ latches.disconnected.open
      }
    }

    class ChannelCallbackActor(latches: ChannelLatches) extends Actor {

      def receive = {
        case Started ⇒ {
          if (!latches.started.isOpen) {
            latches.started.open
          } else {
            latches.restarted.open
          }
        }
        case Restarting ⇒ latches.restarting.open
        case Stopped    ⇒ latches.stopped.open
      }
    }

    val workers = 15
    val messages = 100
    val maxRandomWaitMs = 2000

    val settings = AMQP(system)
    implicit val timeout = Timeout(settings.Timeout)

    val log = Logging(system, "LoadBalancingDemo")

    val countDownLatch = new TestLatch(messages)

    // defaults to amqp://guest:guest@localhost:5672/
    val connection = AMQP.newConnection(context, ConnectionParameters(), Option("connection"))

    // specifies how many messages the amqp channel should
    // prefetch as unacknowledged messages before processing
    // 0 = unlimited
    val smallPrefetchChannelParameters = Some(ChannelParameters(prefetchSize = 1))

    val producerChannelParameters = Some(ChannelParameters())

    val directExchangeParameters = ExchangeParameters("my_direct_exchange", Direct)

    val someRoutingKey = "some.routing.key"

    // consumer
    class JobConsumer(id: Int) extends Actor {
      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒
          log.info("{} received message: {}", self.path, new String(payload.toArray, utf8Charset.name))
          system.scheduler.scheduleOnce(Random.nextInt(maxRandomWaitMs) milliseconds)(countDownLatch.countDown)
        // if you want to see the value of non-blocking, change this to:
        // TimeUnit.MILLISECONDS.sleep(Random.nextInt(maxRandomWaitMs))
        //  countDownLatch.countDown
      }
    }

    // consumers
    val consumers = for (i ← 1 to workers) yield {
      connection ? ConsumerRequest(ConsumerParameters(
        routingKey = someRoutingKey,
        deliveryHandler = context.actorOf(Props(new JobConsumer(i)), "jobconsumer-" + new UUID().toString),
        queueName = Some("my-job-queue"),
        exchangeParameters = Some(directExchangeParameters),
        channelParameters = smallPrefetchChannelParameters)) mapTo manifest[ActorRef]
    }

    val producer = connection ? ProducerRequest(ProducerParameters(Some(directExchangeParameters),
      channelParameters = producerChannelParameters)) mapTo manifest[ActorRef]

    for (cs ← consumers; p ← producer) {
      for (i ← 1 to messages) {
        p ! Message(("data (" + i + ")").getBytes(utf8Charset).toSeq, someRoutingKey)
      }
      log.info("Sent all {} messages - awaiting processing...", messages)
    }

    Await.result(countDownLatch, ((maxRandomWaitMs * messages) + 1000) milliseconds)

    system.shutdown
  }
}