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
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).buffer(100, overflowStrategy = OverflowStrategy.backpressure).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 100) autoProducer.sendNext(i)

      // drain
      for (i ← 1 to 100) {
        sub.requestMore(1)
        consumer.expectNext(i)
      }
      sub.cancel()
    }

    "drop head elements if buffer is full and configured so" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).buffer(100, overflowStrategy = OverflowStrategy.dropHead).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 200) autoProducer.sendNext(i)

      // drain
      for (i ← 101 to 200) {
        sub.requestMore(1)
        consumer.expectNext(i)
      }

      sub.requestMore(1)
      consumer.expectNoMsg(1.seconds)

      autoProducer.sendNext(-1)
      sub.requestMore(1)
      consumer.expectNext(-1)

      sub.cancel()
    }

    "drop tail elements if buffer is full and configured so" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).buffer(100, overflowStrategy = OverflowStrategy.dropTail).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 200) autoProducer.sendNext(i)

      // drain
      for (i ← 1 to 99) {
        sub.requestMore(1)
        consumer.expectNext(i)
      }

      sub.requestMore(1)
      consumer.expectNext(200)

      sub.requestMore(1)
      consumer.expectNoMsg(1.seconds)

      autoProducer.sendNext(-1)
      sub.requestMore(1)
      consumer.expectNext(-1)

      sub.cancel()
    }

    "drop all elements if buffer is full and configured so" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).buffer(100, overflowStrategy = OverflowStrategy.dropBuffer).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 150) autoProducer.sendNext(i)

      // drain
      for (i ← 101 to 150) {
        sub.requestMore(1)
        consumer.expectNext(i)
      }

      sub.requestMore(1)
      consumer.expectNoMsg(1.seconds)

      autoProducer.sendNext(-1)
      sub.requestMore(1)
      consumer.expectNext(-1)

      sub.cancel()
    }

    for (strategy ← List(OverflowStrategy.dropHead, OverflowStrategy.dropTail, OverflowStrategy.dropBuffer)) {

      s"work with $strategy if buffer size of one" in {

        val producer = StreamTestKit.producerProbe[Int]
        val consumer = StreamTestKit.consumerProbe[Int]

        Flow(producer).buffer(1, overflowStrategy = strategy).produceTo(materializer, consumer)

        val autoProducer = new StreamTestKit.AutoProducer(producer)
        val sub = consumer.expectSubscription()

        // Fill up buffer
        for (i ← 1 to 200) autoProducer.sendNext(i)

        sub.requestMore(1)
        consumer.expectNext(200)

        sub.requestMore(1)
        consumer.expectNoMsg(1.seconds)

        autoProducer.sendNext(-1)
        sub.requestMore(1)
        consumer.expectNext(-1)

        sub.cancel()
      }
    }

  }
}
