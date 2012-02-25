/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import akka.actor._
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import util.Random
import java.util.UUID
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch.Await
import akka.pattern.ask
import com.typesafe.config.ConfigFactory

object LoadBalancingDemo {

  def main(args: Array[String]) {

    val workers = 15
    val messages = 100
    val maxRandomWaitMs = 5000

    val system = ActorSystem.create("LoadBalancingDemo", ConfigFactory.load.getConfig("example"))

    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    val amqp = system.actorOf(Props(new AMQPActor))
    // defaults to amqp://guest:guest@localhost:5672/
    val localConnection = Await.result((amqp ? ConnectionRequest(ConnectionParameters())) mapTo manifest[ActorRef], timeout.duration)

    val directExchangeParameters = ExchangeParameters("my_direct_exchange", Direct)
    // specifies how many messages the amqp channel should
    // prefetch as unacknowledged messages before processing
    // 0 = unlimited
    val smallPrefetchChannelParameters = Some(ChannelParameters(prefetchSize = 1))
    val someRoutingKey = "some.routing.key"

    val countDownLatch = new CountDownLatch(messages)

    // consumer
    class JobConsumer(id: Int) extends Actor {

      def receive = {
        case Delivery(payload, _, _, _, _, _) ⇒
          println(self.path + " received message: " + new String(payload))
          TimeUnit.MILLISECONDS.sleep(Random.nextInt(maxRandomWaitMs))
          countDownLatch.countDown
      }
    }

    // consumers
    for (i ← 1 to workers) {

      localConnection ? ConsumerRequest(ConsumerParameters(
        routingKey = someRoutingKey,
        deliveryHandler = system.actorOf(Props(new JobConsumer(i)), "jobconsumer-" + UUID.randomUUID.toString),
        queueName = Some("my-job-queue"),
        exchangeParameters = Some(directExchangeParameters),
        channelParameters = smallPrefetchChannelParameters))
    }

    // producer
    val producer = Await.result((localConnection ? ProducerRequest(ProducerParameters(
      exchangeParameters = Some(directExchangeParameters)))) mapTo manifest[ActorRef], timeout.duration)

    //
    for (i ← 1 to messages) {
      producer ! Message(("data (" + i + ")").getBytes, someRoutingKey)
    }
    println("Sent all " + messages + " messages - awaiting processing...")

    countDownLatch.await((maxRandomWaitMs * messages) + 1000, TimeUnit.MILLISECONDS)

    system.shutdown
    System.exit(0)
  }
}