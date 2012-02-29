/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import akka.actor._
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import util.Random
import java.util.UUID
import akka.dispatch.Await
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import akka.amqp._
import akka.event.Logging
import akka.testkit.TestLatch
import akka.util.duration._
import java.nio.charset.Charset

object LoadBalancingDemo {

  def main(args: Array[String]) {

    implicit val system = ActorSystem.create("LoadBalancingDemo", ConfigFactory.load.getConfig("example"))
    val utf8Charset = Charset.forName("UTF-8")

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

    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    val log = Logging(system, "LoadBalancingDemo")

    val connectionLatches = ConnectionLatches()
    val consumerLatches = ChannelLatches(started = new TestLatch(workers), stopped = new TestLatch(workers))
    val producerLatches = ChannelLatches()
    val countDownLatch = new TestLatch(messages)

    val amqp = system.actorOf(Props(new AMQPActor))
    // defaults to amqp://guest:guest@localhost:5672/

    val connection = (amqp ? ConnectionRequest(ConnectionParameters(connectionCallback =
      Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))) mapTo manifest[ActorRef]

    // specifies how many messages the amqp channel should
    // prefetch as unacknowledged messages before processing
    // 0 = unlimited
    val smallPrefetchChannelParameters = Some(ChannelParameters(prefetchSize = 1,
      channelCallback = Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches))))))

    val producerChannelParameters = Some(ChannelParameters(
      channelCallback = Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches))))))

    val directExchangeParameters = ExchangeParameters("my_direct_exchange", Direct)

    val someRoutingKey = "some.routing.key"

    // consumer
    class JobConsumer(id: Int) extends Actor {
      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒
          log.info("{} received message: {}", self.path, new String(payload.toArray, utf8Charset.name))
          TimeUnit.MILLISECONDS.sleep(Random.nextInt(maxRandomWaitMs))
          countDownLatch.countDown
      }
    }

    // consumers
    for (i ← 1 to workers) {
      val consumer = connection map (_ ! ConsumerRequest(ConsumerParameters(
        routingKey = someRoutingKey,
        deliveryHandler = system.actorOf(Props(new JobConsumer(i)), "jobconsumer-" + UUID.randomUUID.toString),
        queueName = Some("my-job-queue"),
        exchangeParameters = Some(directExchangeParameters),
        channelParameters = smallPrefetchChannelParameters)))
    }

    // producer

    val producer = for {
      conn ← connection
      pr ← (conn ? ProducerRequest(ProducerParameters(Some(directExchangeParameters),
        channelParameters = producerChannelParameters))) mapTo manifest[ActorRef]
    } yield pr

    Await.result(consumerLatches.started, timeout.duration)
    Await.result(producerLatches.started, timeout.duration)

    val p = Await.result(producer, timeout.duration)

    for (i ← 1 to messages) {
      p ! Message(("data (" + i + ")").getBytes(utf8Charset).toSeq, someRoutingKey)
    }

    log.info("Sent all {} messages - awaiting processing...", messages)

    Await.result(countDownLatch, ((maxRandomWaitMs * messages) + 1000) milliseconds)

    system.shutdown
  }
}