/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import scala.concurrent.duration._
import scala.concurrent.Await
import org.reactivestreams.Publisher
import scala.util.control.NoStackTrace
import akka.stream.MaterializerSettings

class FlowConcatAllSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  "ConcatAll" must {

    val testException = new Exception("test") with NoStackTrace

    "work in the happy case" in {
      val s1 = Source((1 to 2).iterator)
      val s2 = Source(List.empty[Int])
      val s3 = Source(List(3))
      val s4 = Source((4 to 6).iterator)
      val s5 = Source((7 to 10).iterator)

      val main = Source(List(s1, s2, s3, s4, s5))

      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      main.flatten(FlattenStrategy.concat).publishTo(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(10)
      subscriber.probe.receiveN(10) should be((1 to 10).map(StreamTestKit.OnNext(_)))
      subscription.request(1)
      subscriber.expectComplete()
    }

    "work together with SplitWhen" in {
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Source((1 to 10).iterator).splitWhen(_ % 2 == 0).flatten(FlattenStrategy.concat).publishTo(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(10)
      subscriber.probe.receiveN(10) should be((1 to 10).map(StreamTestKit.OnNext(_)))
      subscription.request(1)
      subscriber.expectComplete()
    }

    "on onError on master stream cancel the current open substream and signal error" in {
      val publisher = StreamTestKit.PublisherProbe[Source[Int]]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Source(publisher).flatten(FlattenStrategy.concat).publishTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = StreamTestKit.PublisherProbe[Int]()
      val substreamFlow = Source(substreamPublisher)
      upstream.expectRequest()
      upstream.sendNext(substreamFlow)
      val subUpstream = substreamPublisher.expectSubscription()

      upstream.sendError(testException)
      subscriber.expectError(testException)
      subUpstream.expectCancellation()
    }

    "on onError on open substream, cancel the master stream and signal error " in {
      val publisher = StreamTestKit.PublisherProbe[Source[Int]]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Source(publisher).flatten(FlattenStrategy.concat).publishTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = StreamTestKit.PublisherProbe[Int]()
      val substreamFlow = Source(substreamPublisher)
      upstream.expectRequest()
      upstream.sendNext(substreamFlow)
      val subUpstream = substreamPublisher.expectSubscription()

      subUpstream.sendError(testException)
      subscriber.expectError(testException)
      upstream.expectCancellation()
    }

    "on cancellation cancel the current open substream and the master stream" in {
      val publisher = StreamTestKit.PublisherProbe[Source[Int]]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Source(publisher).flatten(FlattenStrategy.concat).publishTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = StreamTestKit.PublisherProbe[Int]()
      val substreamFlow = Source(substreamPublisher)
      upstream.expectRequest()
      upstream.sendNext(substreamFlow)
      val subUpstream = substreamPublisher.expectSubscription()

      downstream.cancel()

      subUpstream.expectCancellation()
      upstream.expectCancellation()
    }

  }

}
