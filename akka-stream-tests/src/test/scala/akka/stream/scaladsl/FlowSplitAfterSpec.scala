/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorAttributes
import akka.stream.Supervision.resumingDecider
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.Utils._
import org.reactivestreams.Publisher

import scala.concurrent.duration._

class FlowSplitAfterSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  case class StreamPuppet(p: Publisher[Int]) {
    val probe = TestSubscriber.manualProbe[Int]()
    p.subscribe(probe)
    val subscription = probe.expectSubscription()

    def request(demand: Int): Unit = subscription.request(demand)
    def expectNext(elem: Int): Unit = probe.expectNext(elem)
    def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)
    def expectComplete(): Unit = probe.expectComplete()
    def expectError(e: Throwable) = probe.expectError(e)
    def cancel(): Unit = subscription.cancel()
  }

  class SubstreamsSupport(splitAfter: Int = 3, elementCount: Int = 6) {
    val source = Source(1 to elementCount)
    val groupStream = source.splitAfter(_ == splitAfter).runWith(Sink.publisher(false))
    val masterSubscriber = TestSubscriber.manualProbe[Source[Int, _]]()

    groupStream.subscribe(masterSubscriber)
    val masterSubscription = masterSubscriber.expectSubscription()

    def expectSubFlow(): Source[Int, _] = {
      masterSubscription.request(1)
      expectSubPublisher()
    }

    def expectSubPublisher(): Source[Int, _] = {
      val substream = masterSubscriber.expectNext()
      substream
    }

  }

  "splitAfter" must {

    "work in the happy case" in assertAllStagesStopped {
      new SubstreamsSupport(3, elementCount = 5) {
        val s1 = StreamPuppet(expectSubFlow().runWith(Sink.publisher(false)))
        masterSubscriber.expectNoMsg(100.millis)

        s1.request(2)
        s1.expectNext(1)
        s1.expectNext(2)
        s1.request(1)
        s1.expectNext(3)
        s1.request(1)
        s1.expectComplete()

        val s2 = StreamPuppet(expectSubFlow().runWith(Sink.publisher(false)))

        s2.request(2)
        s2.expectNext(4)
        s2.expectNext(5)
        s2.expectComplete()

        masterSubscriber.expectComplete()
      }
    }

    "work when first element is split-by" in assertAllStagesStopped {
      new SubstreamsSupport(splitAfter = 1, elementCount = 3) {
        val s1 = StreamPuppet(expectSubFlow().runWith(Sink.publisher(false)))
        masterSubscriber.expectNoMsg(100.millis)

        s1.request(3)
        s1.expectNext(1)
        s1.expectComplete()

        val s2 = StreamPuppet(expectSubFlow().runWith(Sink.publisher(false)))

        s2.request(3)
        s2.expectNext(2)
        s2.expectNext(3)
        s2.expectComplete()

        masterSubscriber.expectComplete()
      }
    }

    "support cancelling substreams" in assertAllStagesStopped {
      new SubstreamsSupport(splitAfter = 5, elementCount = 8) {
        val s1 = StreamPuppet(expectSubFlow().runWith(Sink.publisher(false)))
        s1.cancel()
        val s2 = StreamPuppet(expectSubFlow().runWith(Sink.publisher(false)))

        s2.request(4)
        s2.expectNext(6)
        s2.expectNext(7)
        s2.expectNext(8)
        s2.expectComplete()

        masterSubscription.request(1)
        masterSubscriber.expectComplete()
      }
    }

    "support cancelling the master stream" in assertAllStagesStopped {
      new SubstreamsSupport(splitAfter = 5, elementCount = 8) {
        val s1 = StreamPuppet(expectSubFlow().runWith(Sink.publisher(false)))
        masterSubscription.cancel()
        s1.request(5)
        s1.expectNext(1)
        s1.expectNext(2)
        s1.expectNext(3)
        s1.expectNext(4)
        s1.expectNext(5)
        s1.request(1)
        s1.expectComplete()
      }
    }

    "fail stream when splitAfter function throws" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val exc = TE("test")
      val publisher = Source(publisherProbeProbe)
        .splitAfter(elem ⇒ if (elem == 3) throw exc else elem % 3 == 0)
        .runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[Source[Int, Unit]]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val substream = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream.runWith(Sink.publisher(false)))

      substreamPuppet.request(10)
      substreamPuppet.expectNext(1)

      upstreamSubscription.sendNext(2)
      substreamPuppet.expectNext(2)

      upstreamSubscription.sendNext(3)

      subscriber.expectError(exc)
      substreamPuppet.expectError(exc)
      upstreamSubscription.expectCancellation()
    }

    "resume stream when splitAfter function throws" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val exc = TE("test")
      val publisher = Source(publisherProbeProbe)
        .splitAfter(elem ⇒ if (elem == 3) throw exc else elem % 3 == 0)
        .withAttributes(ActorAttributes.supervisionStrategy(resumingDecider))
        .runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[Source[Int, Unit]]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val substream1 = subscriber.expectNext()
      val substreamPuppet1 = StreamPuppet(substream1.runWith(Sink.publisher(false)))

      substreamPuppet1.request(10)
      substreamPuppet1.expectNext(1)

      upstreamSubscription.sendNext(2)
      substreamPuppet1.expectNext(2)

      upstreamSubscription.sendNext(3)
      upstreamSubscription.sendNext(4)
      substreamPuppet1.expectNext(4) // note that 3 was dropped

      upstreamSubscription.sendNext(5)
      substreamPuppet1.expectNext(5)

      upstreamSubscription.sendNext(6)
      substreamPuppet1.expectNext(6)
      substreamPuppet1.expectComplete()
      val substream2 = subscriber.expectNext()
      val substreamPuppet2 = StreamPuppet(substream2.runWith(Sink.publisher(false)))
      substreamPuppet2.request(10)
      upstreamSubscription.sendNext(7)
      substreamPuppet2.expectNext(7)

      upstreamSubscription.sendComplete()
      subscriber.expectComplete()
      substreamPuppet2.expectComplete()
    }

    "pass along early cancellation" in assertAllStagesStopped {
      val up = TestPublisher.manualProbe[Int]()
      val down = TestSubscriber.manualProbe[Source[Int, Unit]]()

      val flowSubscriber = Source.subscriber[Int].splitAfter(_ % 3 == 0).to(Sink(down)).run()

      val downstream = down.expectSubscription()
      downstream.cancel()
      up.subscribe(flowSubscriber)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

  }

}
