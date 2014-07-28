/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import akka.stream.scaladsl.Flow
import scala.concurrent.duration._
import scala.concurrent.Await
import org.reactivestreams.Publisher
import scala.util.control.NoStackTrace

class FlowConcatAllSpec extends AkkaSpec {

  val m = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "ConcatAll" must {

    val testException = new Exception("test") with NoStackTrace

    "work in the happy case" in {
      val s1 = Flow((1 to 2).iterator).toPublisher(m)
      val s2 = Flow(List.empty[Int]).toPublisher(m)
      val s3 = Flow(List(3)).toPublisher(m)
      val s4 = Flow((4 to 6).iterator).toPublisher(m)
      val s5 = Flow((7 to 10).iterator).toPublisher(m)

      val main: Flow[Publisher[Int]] = Flow(List(s1, s2, s3, s4, s5))

      Await.result(main.flatten(FlattenStrategy.concat).grouped(10).toFuture(m), 3.seconds) should be(1 to 10)
    }

    "work together with SplitWhen" in {
      Await.result(
        Flow((1 to 10).iterator).splitWhen(_ % 2 == 0).flatten(FlattenStrategy.concat).grouped(10).toFuture(m),
        3.seconds) should be(1 to 10)
    }

    "on onError on master stream cancel the current open substream and signal error" in {
      val publisher = StreamTestKit.PublisherProbe[Publisher[Int]]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Flow(publisher).flatten(FlattenStrategy.concat).produceTo(m, subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = StreamTestKit.PublisherProbe[Int]()
      upstream.expectRequest()
      upstream.sendNext(substreamPublisher)
      val subUpstream = substreamPublisher.expectSubscription()

      upstream.sendError(testException)
      subscriber.expectError(testException)
      subUpstream.expectCancellation()
    }

    "on onError on open substream, cancel the master stream and signal error " in {
      val publisher = StreamTestKit.PublisherProbe[Publisher[Int]]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Flow(publisher).flatten(FlattenStrategy.concat).produceTo(m, subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = StreamTestKit.PublisherProbe[Int]()
      upstream.expectRequest()
      upstream.sendNext(substreamPublisher)
      val subUpstream = substreamPublisher.expectSubscription()

      subUpstream.sendError(testException)
      subscriber.expectError(testException)
      upstream.expectCancellation()
    }

    "on cancellation cancel the current open substream and the master stream" in {
      val publisher = StreamTestKit.PublisherProbe[Publisher[Int]]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Flow(publisher).flatten(FlattenStrategy.concat).produceTo(m, subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = StreamTestKit.PublisherProbe[Int]()
      upstream.expectRequest()
      upstream.sendNext(substreamPublisher)
      val subUpstream = substreamPublisher.expectSubscription()

      downstream.cancel()

      subUpstream.expectCancellation()
      upstream.expectCancellation()
    }

  }

}
