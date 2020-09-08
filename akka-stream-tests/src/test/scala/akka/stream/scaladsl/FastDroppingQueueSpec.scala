/*
 * Copyright (C) 2020-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.{ CountDownLatch, ThreadLocalRandom }
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.impl.FastDroppingQueue
import FastDroppingQueue.OfferResult
import akka.stream.testkit.TestSubscriber
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.MustMatchers
import org.scalatest.WordSpec

import scala.concurrent.duration._

class FastDroppingQueueSpec extends WordSpec with BeforeAndAfterAll with MustMatchers with ScalaFutures {
  implicit val system = ActorSystem("SimpleQueueSpec")
  implicit val mat = ActorMaterializer()
  implicit val ec = system.dispatcher

  "SimpleQueue" should {
    "not drop elements if buffer is not full" in {
      val sub = TestSubscriber.probe[Int]()
      val queue =
        FastDroppingQueue[Int](100).toMat(Sink.fromSubscriber(sub))(Keep.left).run()

      val elements = 1 to 100

      elements.foreach { i =>
        queue.offer(i) mustBe OfferResult.Enqueued
      }
      queue.complete()

      val subIt = Iterator.continually(sub.requestNext())
      subIt.zip(elements.iterator).foreach {
        case (subEle, origEle) => subEle mustBe origEle
      }
      sub.expectComplete()
    }
    "drop elements if buffer is full" in {
      val sub = TestSubscriber.probe[Int]()
      val queue =
        FastDroppingQueue[Int](10).toMat(Sink.fromSubscriber(sub))(Keep.left).run()

      val elements = 1 to 100

      val histo =
        elements
          .map { i =>
            queue.offer(i)
          }
          .groupBy(identity)
          .mapValues(_.size)

      // it should be 100 elements - 10 buffer slots = 90, but there might be other implicit buffers involved
      histo(OfferResult.Dropped) must be > 80
    }
    "without cancellation only flag elements as enqueued that will also passed to downstream" in {
      val counter = new AtomicLong()
      val (queue, result) =
        FastDroppingQueue[Int](100000).toMat(Sink.fold(0L)(_ + _))(Keep.both).run()

      val numThreads = 32
      val stopProb = 1000000
      val expected = 1d / (1d - math.pow(1d - 1d / stopProb, numThreads))
      println(s"Expected elements per thread: $expected") // variance might be quite high depending on number of threads
      val barrier = new CountDownLatch(numThreads)

      class QueueingThread extends Thread {
        override def run(): Unit = {
          var numElemsEnqueued = 0
          var numElemsDropped = 0
          def runLoop(): Unit = {
            val r = ThreadLocalRandom.current()

            while (true) {
              val i = r.nextInt(0, Int.MaxValue)
              queue.offer(i) match {
                case OfferResult.Enqueued =>
                  counter.addAndGet(i)
                  numElemsEnqueued += 1
                case OfferResult.Dropped =>
                  numElemsDropped += 1
                case _: OfferResult.CompletionResult => return // other thread completed
              }

              if ((i % stopProb) == 0) { // probabilistic exit condition
                queue.complete()
                return
              }

              if (i % 100 == 0) Thread.sleep(1) // probabilistic producer throttling delay
            }
          }

          barrier.countDown()
          barrier.await() // wait for all threads being in this state before starting race
          runLoop()
          println(f"Thread $getName%-20s enqueued: $numElemsEnqueued%7d dropped: $numElemsDropped%7d before completion")
        }
      }

      (1 to numThreads).foreach { i =>
        val t = new QueueingThread
        t.setName(s"QueuingThread-$i")
        t.start()
      }

      result.futureValue mustBe counter.get()
    }

  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(5.seconds)

  override protected def afterAll(): Unit = system.terminate()
}
