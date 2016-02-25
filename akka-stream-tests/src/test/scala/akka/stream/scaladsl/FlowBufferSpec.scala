/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.stream.{ BufferOverflowException, ActorMaterializer, ActorMaterializerSettings, OverflowStrategy }
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import akka.stream.testkit.Utils._
import akka.testkit.AkkaSpec

class FlowBufferSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer = ActorMaterializer(settings)

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

    "pass elements through a chain of backpressured buffers of different size" in assertAllStagesStopped {
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
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(publisher).buffer(100, overflowStrategy = OverflowStrategy.backpressure).to(Sink.fromSubscriber(subscriber)).run()
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 100) publisher.sendNext(i)

      // drain
      for (i ← 1 to 100) {
        sub.request(1)
        subscriber.expectNext(i)
      }
      sub.cancel()
    }

    "drop head elements if buffer is full and configured so" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropHead).to(Sink.fromSubscriber(subscriber)).run()
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 200) publisher.sendNext(i)

      // The next request would  be otherwise in race with the last onNext in the above loop
      subscriber.expectNoMsg(500.millis)

      // drain
      for (i ← 101 to 200) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      sub.request(1)
      subscriber.expectNoMsg(1.seconds)

      publisher.sendNext(-1)
      sub.request(1)
      subscriber.expectNext(-1)

      sub.cancel()
    }

    "drop tail elements if buffer is full and configured so" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropTail).to(Sink.fromSubscriber(subscriber)).run()
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 200) publisher.sendNext(i)

      // The next request would  be otherwise in race with the last onNext in the above loop
      subscriber.expectNoMsg(500.millis)

      // drain
      for (i ← 1 to 99) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      sub.request(1)
      subscriber.expectNext(200)

      sub.request(1)
      subscriber.expectNoMsg(1.seconds)

      publisher.sendNext(-1)
      sub.request(1)
      subscriber.expectNext(-1)

      sub.cancel()
    }

    "drop all elements if buffer is full and configured so" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(publisher).buffer(100, overflowStrategy = OverflowStrategy.dropBuffer).to(Sink.fromSubscriber(subscriber)).run()
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 150) publisher.sendNext(i)

      // The next request would  be otherwise in race with the last onNext in the above loop
      subscriber.expectNoMsg(500.millis)

      // drain
      for (i ← 101 to 150) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      sub.request(1)
      subscriber.expectNoMsg(1.seconds)

      publisher.sendNext(-1)
      sub.request(1)
      subscriber.expectNext(-1)

      sub.cancel()
    }

    "drop new elements if buffer is full and configured so" in {
      val (publisher, subscriber) = TestSource.probe[Int].buffer(100, overflowStrategy = OverflowStrategy.dropNew).toMat(TestSink.probe[Int])(Keep.both).run()

      subscriber.ensureSubscription()

      // Fill up buffer
      for (i ← 1 to 150) publisher.sendNext(i)

      // The next request would  be otherwise in race with the last onNext in the above loop
      subscriber.expectNoMsg(500.millis)

      // drain
      for (i ← 1 to 100) {
        subscriber.requestNext(i)
      }

      subscriber.request(1)
      subscriber.expectNoMsg(1.seconds)

      publisher.sendNext(-1)
      subscriber.requestNext(-1)

      subscriber.cancel()
    }

    "fail upstream if buffer is full and configured so" in assertAllStagesStopped {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(publisher).buffer(100, overflowStrategy = OverflowStrategy.fail).to(Sink.fromSubscriber(subscriber)).run()
      val sub = subscriber.expectSubscription()

      // Fill up buffer
      for (i ← 1 to 100) publisher.sendNext(i)

      // drain
      for (i ← 1 to 10) {
        sub.request(1)
        subscriber.expectNext(i)
      }

      // overflow the buffer
      for (i ← 101 to 111) publisher.sendNext(i)

      publisher.expectCancellation()
      val error = new BufferOverflowException("Buffer overflow (max capacity was: 100)!")
      subscriber.expectError(error)
    }

    for (strategy ← List(OverflowStrategy.dropHead, OverflowStrategy.dropTail, OverflowStrategy.dropBuffer)) {

      s"work with $strategy if buffer size of one" in {

        val publisher = TestPublisher.probe[Int]()
        val subscriber = TestSubscriber.manualProbe[Int]()

        Source.fromPublisher(publisher).buffer(1, overflowStrategy = strategy).to(Sink.fromSubscriber(subscriber)).run()
        val sub = subscriber.expectSubscription()

        // Fill up buffer
        for (i ← 1 to 200) publisher.sendNext(i)

        // The request below is in race otherwise with the onNext(200) above
        subscriber.expectNoMsg(500.millis)
        sub.request(1)
        subscriber.expectNext(200)

        sub.request(1)
        subscriber.expectNoMsg(1.seconds)

        publisher.sendNext(-1)
        sub.request(1)
        subscriber.expectNext(-1)

        sub.cancel()
      }
    }

  }
}
