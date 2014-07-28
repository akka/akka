/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import akka.stream.scaladsl.Flow
import scala.concurrent.Await
import scala.concurrent.duration._
import OverflowStrategy._

class FlowBufferSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 1,
    maximumInputBufferSize = 1,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 1,
    dispatcher = "akka.test.stream-dispatcher"))

  "Buffer" must {

    "pass elements through normally in backpressured mode" in {
      val future = Flow((1 to 1000).iterator).buffer(100, overflowStrategy = OverflowStrategy.backpressure).grouped(1001).toFuture(materializer)
      Await.result(future, 3.seconds) should be(1 to 1000)
    }

    "pass elements through normally in backpressured mode with buffer size one" in {
      val future = Flow((1 to 1000).iterator).buffer(1, overflowStrategy = OverflowStrategy.backpressure).grouped(1001).toFuture(materializer)
      Await.result(future, 3.seconds) should be(1 to 1000)
    }

    "pass elements through a chain of backpressured buffers of different size" in {
      val future = Flow((1 to 1000).iterator)
        .buffer(1, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(10, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(256, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(1, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(5, overflowStrategy = OverflowStrategy.backpressure)
        .buffer(128, overflowStrategy = OverflowStrategy.backpressure)
        .grouped(1001)
        .toFuture(materializer)
      Await.result(future, 3.seconds) should be(1 to 1000)
    }

    "accept elements that fit in the buffer while downstream is silent" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Flow(publisher).buffer(100, overflowStrategy = OverflowStrategy.backpressure).produceTo(materializer, subscriber)

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

      Flow(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropHead).produceTo(materializer, subscriber)

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

      Flow(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropTail).produceTo(materializer, subscriber)

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

      Flow(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropBuffer).produceTo(materializer, subscriber)

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

    for (strategy ← List(OverflowStrategy.dropHead, OverflowStrategy.dropTail, OverflowStrategy.dropBuffer)) {

      s"work with $strategy if buffer size of one" in {

        val publisher = StreamTestKit.PublisherProbe[Int]
        val subscriber = StreamTestKit.SubscriberProbe[Int]()

        Flow(publisher).buffer(1, overflowStrategy = strategy).produceTo(materializer, subscriber)

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
