/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.TestPublisher._
import akka.stream.testkit.TestSubscriber._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import org.reactivestreams.Subscription
import akka.testkit.AkkaSpec

class TestPublisherSubscriberSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  "TestPublisher and TestSubscriber" must {

    "have all events accessible from manual probes" in assertAllStagesStopped {
      val upstream = TestPublisher.manualProbe[Int]()
      val downstream = TestSubscriber.manualProbe[Int]()
      Source.fromPublisher(upstream).runWith(Sink.asPublisher(false))(materializer).subscribe(downstream)

      val upstreamSubscription = upstream.expectSubscription()
      val downstreamSubscription: Subscription = downstream.expectEventPF { case OnSubscribe(sub) ⇒ sub }

      upstreamSubscription.sendNext(1)
      downstreamSubscription.request(1)
      upstream.expectEventPF { case RequestMore(_, e) ⇒ e } should ===(1L)
      downstream.expectEventPF { case OnNext(e) ⇒ e } should ===(1)

      upstreamSubscription.sendNext(1)
      downstreamSubscription.request(1)
      downstream.expectNextPF[Int] { case e: Int ⇒ e } should ===(1)

      upstreamSubscription.sendComplete()
      downstream.expectEventPF {
        case OnComplete ⇒
        case _          ⇒ fail()
      }
    }

    "handle gracefully partial function that is not suitable" in assertAllStagesStopped {
      val upstream = TestPublisher.manualProbe[Int]()
      val downstream = TestSubscriber.manualProbe[Int]()
      Source.fromPublisher(upstream).runWith(Sink.asPublisher(false))(materializer).subscribe(downstream)
      val upstreamSubscription = upstream.expectSubscription()
      val downstreamSubscription: Subscription = downstream.expectEventPF { case OnSubscribe(sub) ⇒ sub }

      upstreamSubscription.sendNext(1)
      downstreamSubscription.request(1)
      an[AssertionError] should be thrownBy upstream.expectEventPF { case Subscribe(e) ⇒ e }
      an[AssertionError] should be thrownBy downstream.expectNextPF[String] { case e: String ⇒ e }

      upstreamSubscription.sendComplete()
    }

    "properly update pendingRequest in expectRequest" in {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(upstream).runWith(Sink.fromSubscriber(downstream))

      downstream
        .expectSubscription()
        .request(10)

      upstream.expectRequest() should ===(10L)
      upstream.sendNext(1)
      downstream.expectNext(1)
    }

  }
}
