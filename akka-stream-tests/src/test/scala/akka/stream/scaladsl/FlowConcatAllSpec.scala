/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class FlowConcatAllSpec extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorFlowMaterializer(settings)

  "ConcatAll" must {

    val testException = new Exception("test") with NoStackTrace

    "work in the happy case" in assertAllStagesStopped {
      val s1 = Source(1 to 2)
      val s2 = Source(List.empty[Int])
      val s3 = Source(List(3))
      val s4 = Source(4 to 6)
      val s5 = Source(7 to 10)

      val main = Source(List(s1, s2, s3, s4, s5))

      val subscriber = TestSubscriber.manualProbe[Int]()
      main.flatten(FlattenStrategy.concat).to(Sink(subscriber)).run()
      val subscription = subscriber.expectSubscription()
      subscription.request(10)
      for (i ← 1 to 10)
        subscriber.expectNext() shouldBe i
      subscription.request(1)
      subscriber.expectComplete()
    }

    "work together with SplitWhen" in {
      val subscriber = TestSubscriber.manualProbe[Int]()
      Source(1 to 10).splitWhen(_ % 2 == 0).flatten(FlattenStrategy.concat).runWith(Sink(subscriber))
      val subscription = subscriber.expectSubscription()
      subscription.request(10)
      for (i ← (1 to 10))
        subscriber.expectNext() shouldBe i
      subscription.request(1)
      subscriber.expectComplete()
    }

    "on onError on master stream cancel the current open substream and signal error" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Source[Int, _]]()
      val subscriber = TestSubscriber.manualProbe[Int]()
      Source(publisher).flatten(FlattenStrategy.concat).to(Sink(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = TestPublisher.manualProbe[Int]()
      val substreamFlow = Source(substreamPublisher)
      upstream.expectRequest()
      upstream.sendNext(substreamFlow)
      val subUpstream = substreamPublisher.expectSubscription()

      upstream.sendError(testException)
      subscriber.expectError(testException)
      subUpstream.expectCancellation()
    }

    "on onError on open substream, cancel the master stream and signal error " in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Source[Int, _]]()
      val subscriber = TestSubscriber.manualProbe[Int]()
      Source(publisher).flatten(FlattenStrategy.concat).to(Sink(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = TestPublisher.manualProbe[Int]()
      val substreamFlow = Source(substreamPublisher)
      upstream.expectRequest()
      upstream.sendNext(substreamFlow)
      val subUpstream = substreamPublisher.expectSubscription()

      subUpstream.sendError(testException)
      subscriber.expectError(testException)
      upstream.expectCancellation()
    }

    "on cancellation cancel the current open substream and the master stream" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Source[Int, _]]()
      val subscriber = TestSubscriber.manualProbe[Int]()
      Source(publisher).flatten(FlattenStrategy.concat).to(Sink(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()
      downstream.request(1000)

      val substreamPublisher = TestPublisher.manualProbe[Int]()
      val substreamFlow = Source(substreamPublisher)
      upstream.expectRequest()
      upstream.sendNext(substreamFlow)
      val subUpstream = substreamPublisher.expectSubscription()

      downstream.cancel()

      subUpstream.expectCancellation()
      upstream.expectCancellation()
    }

    "pass along early cancellation" in assertAllStagesStopped {
      val up = TestPublisher.manualProbe[Source[Int, _]]()
      val down = TestSubscriber.manualProbe[Int]()

      val flowSubscriber = Source.subscriber[Source[Int, _]].flatten(FlattenStrategy.concat).to(Sink(down)).run()

      val downstream = down.expectSubscription()
      downstream.cancel()
      up.subscribe(flowSubscriber)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

  }

}
