/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.OverflowStrategy
import akka.stream.OverflowStrategy.Error.BufferOverflowException
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }

class FlowBufferSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 1, maxSize = 1)
    .withFanOutBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer = FlowMaterializer(settings)

  "Buffer" must {

    "pass elements through normally in backpressured mode" in {
      val future: Future[Seq[Int]] = Source(1 to 1000).buffer(100, overflowStrategy = OverflowStrategy.backpressure).grouped(1001).
        runWith(Sink.head)
      Await.result(future, 3.seconds) should be(1 to 1000)
    }

    "pass elements through normally in backpressured mode with buffer size one" in {
      val futureSink = Sink.head[Seq[Int]]
      val future = Source(1 to 1000).buffer(1, overflowStrategy = OverflowStrategy.backpressure).grouped(1001).
        runWith(Sink.head)
      Await.result(future, 3.seconds) should be(1 to 1000)
    }

    "pass elements through a chain of backpressured buffers of different size" in {
      val future = Source(1 to 1000)
        .buffer(1, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(10, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(256, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(1, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(5, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(128, overflowStrategy = OverflowStrategy.backpressure)
        .grouped(1001)
        .runWith(Sink.head)
      Await.result(future, 3.seconds) should be(1 to 1000)
    }

    "accept elements that fit in the buffer while downstream is silent" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).buffer(100, overflowStrategy = OverflowStrategy.backpressure).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 100) autoPublisher.sendNext(i)

      // drain
      for (i ← 1 to 100) {
        sub.request(1)
        subscriber.expectNext(i)
      }
      sub.cancel()
    }

    "drop head elements if buffer is full and configured so" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropHead).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 200) autoPublisher.sendNext(i)

      // drain
      for (i ← 101 to 200) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      sub.request(1)
      subscriber.expectNoMsg(1.seconds)

      autoPublisher.sendNext(-1)
      sub.request(1)
      subscriber.expectNext(-1)

      sub.cancel()
    }

    "drop tail elements if buffer is full and configured so" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropTail).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 200) autoPublisher.sendNext(i)

      // drain
      for (i ← 1 to 99) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      sub.request(1)
      subscriber.expectNext(200)

      sub.request(1)
      subscriber.expectNoMsg(1.seconds)

      autoPublisher.sendNext(-1)
      sub.request(1)
      subscriber.expectNext(-1)

      sub.cancel()
    }

    "drop all elements if buffer is full and configured so" in {
      val publisher = StreamTestKit.PublisherProbe[Int]
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropBuffer).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 150) autoPublisher.sendNext(i)

      // drain
      for (i ← 101 to 150) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      sub.request(1)
      subscriber.expectNoMsg(1.seconds)

      autoPublisher.sendNext(-1)
      sub.request(1)
      subscriber.expectNext(-1)

      sub.cancel()
    }

    "error upstream if buffer is full and configured so" in {
      val publisher = StreamTestKit.PublisherProbe[Int]
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).buffer(100, overflowStrategy = OverflowStrategy.error).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 100) autoPublisher.sendNext(i)

      // drain
      for (i ← 1 to 10) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      // overflow the buffer
      for (i ← 101 to 111) autoPublisher.sendNext(i)

      autoPublisher.subscription.expectCancellation()
      val error = new BufferOverflowException("Buffer overflow (max capacity was: 100)!")
      subscriber.expectError(error)
    }

    for (strategy ← List(OverflowStrategy.dropHead, OverflowStrategy.dropTail, OverflowStrategy.dropBuffer)) {

      s"work with $strategy if buffer size of one" in {

        val publisher = StreamTestKit.PublisherProbe[Int]
        val subscriber = StreamTestKit.SubscriberProbe[Int]()

        Source(publisher).buffer(1, overflowStrategy = strategy).runWith(Sink(subscriber))

        val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
        val sub = subscriber.expectSubscription()

        // Fill up buffer
        for (i ← 1 to 200) autoPublisher.sendNext(i)

        sub.request(1)
        subscriber.expectNext(200)

        sub.request(1)
        subscriber.expectNoMsg(1.seconds)

        autoPublisher.sendNext(-1)
        sub.request(1)
        subscriber.expectNext(-1)

        sub.cancel()
      }
    }

  }
}
