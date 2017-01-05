/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.Queue

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.Props
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue

object SendQueueSpec {

  case class ProduceToQueue(from: Int, until: Int, queue: Queue[Msg])
  case class ProduceToQueueValue(from: Int, until: Int, queue: SendQueue.QueueValue[Msg])
  case class Msg(fromProducer: String, value: Int)

  def producerProps(producerId: String): Props =
    Props(new Producer(producerId))

  class Producer(producerId: String) extends Actor {
    def receive = {
      case ProduceToQueue(from, until, queue) ⇒
        var i = from
        while (i < until) {
          if (!queue.offer(Msg(producerId, i)))
            throw new IllegalStateException(s"offer failed from $producerId value $i")
          i += 1
        }
      case ProduceToQueueValue(from, until, queue) ⇒
        var i = from
        while (i < until) {
          if (!queue.offer(Msg(producerId, i)))
            throw new IllegalStateException(s"offer failed from $producerId value $i")
          i += 1
        }
    }

  }
}

class SendQueueSpec extends AkkaSpec("akka.actor.serialize-messages = off") with ImplicitSender {
  import SendQueueSpec._

  val matSettings = ActorMaterializerSettings(system).withFuzzing(true)
  implicit val mat = ActorMaterializer(matSettings)(system)

  "SendQueue" must {

    "deliver all messages" in {
      val queue = new ManyToOneConcurrentArrayQueue[String](128)
      val (sendQueue, downstream) = Source.fromGraph(new SendQueue[String])
        .toMat(TestSink.probe)(Keep.both).run()

      downstream.request(10)
      sendQueue.inject(queue)
      sendQueue.offer("a")
      sendQueue.offer("b")
      sendQueue.offer("c")
      downstream.expectNext("a")
      downstream.expectNext("b")
      downstream.expectNext("c")
      downstream.cancel()
    }

    "deliver messages enqueued before materialization" in {
      val queue = new ManyToOneConcurrentArrayQueue[String](128)
      queue.offer("a")
      queue.offer("b")

      val (sendQueue, downstream) = Source.fromGraph(new SendQueue[String])
        .toMat(TestSink.probe)(Keep.both).run()

      downstream.request(10)
      downstream.expectNoMsg(200.millis)
      sendQueue.inject(queue)
      downstream.expectNext("a")
      downstream.expectNext("b")

      sendQueue.offer("c")
      downstream.expectNext("c")
      downstream.cancel()
    }

    "deliver bursts of messages" in {
      // this test verifies that the wakeup signal is triggered correctly
      val queue = new ManyToOneConcurrentArrayQueue[Int](128)
      val burstSize = 100
      val (sendQueue, downstream) = Source.fromGraph(new SendQueue[Int])
        .grouped(burstSize)
        .async
        .toMat(TestSink.probe)(Keep.both).run()

      downstream.request(10)
      sendQueue.inject(queue)

      for (round ← 1 to 100000) {
        for (n ← 1 to burstSize) {
          if (!sendQueue.offer(round * 1000 + n))
            fail(s"offer failed at round $round message $n")
        }
        downstream.expectNext((1 to burstSize).map(_ + round * 1000).toList)
        downstream.request(1)
      }

      downstream.cancel()
    }

    "support multiple producers" in {
      val numberOfProducers = 5
      val queue = new ManyToOneConcurrentArrayQueue[Msg](numberOfProducers * 512)
      val producers = Vector.tabulate(numberOfProducers)(i ⇒ system.actorOf(producerProps(s"producer-$i")))

      // send 100 per producer before materializing
      producers.foreach(_ ! ProduceToQueue(0, 100, queue))

      val (sendQueue, downstream) = Source.fromGraph(new SendQueue[Msg])
        .toMat(TestSink.probe)(Keep.both).run()

      sendQueue.inject(queue)
      producers.foreach(_ ! ProduceToQueueValue(100, 200, sendQueue))

      // send 100 more per producer
      downstream.request(producers.size * 200)
      val msgByProducer = downstream.expectNextN(producers.size * 200).groupBy(_.fromProducer)
      (0 until producers.size).foreach { i ⇒
        msgByProducer(s"producer-$i").map(_.value) should ===(0 until 200)
      }

      // send 500 per producer
      downstream.request(producers.size * 1000) // more than enough
      producers.foreach(_ ! ProduceToQueueValue(200, 700, sendQueue))
      val msgByProducer2 = downstream.expectNextN(producers.size * 500).groupBy(_.fromProducer)
      (0 until producers.size).foreach { i ⇒
        msgByProducer2(s"producer-$i").map(_.value) should ===(200 until 700)
      }

      downstream.cancel()
    }

  }

}
