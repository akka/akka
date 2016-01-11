/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.Supervision.resumingDecider
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import org.reactivestreams.Publisher
import akka.stream.Attributes
import akka.stream.ActorAttributes

class FlowGroupBySpec extends AkkaSpec {

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

  class SubstreamsSupport(groupCount: Int = 2, elementCount: Int = 6) {
    val source = Source(1 to elementCount).runWith(Sink.publisher(false))
    val groupStream = Source(source).groupBy(_ % groupCount).runWith(Sink.publisher(false))
    val masterSubscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()

    groupStream.subscribe(masterSubscriber)
    val masterSubscription = masterSubscriber.expectSubscription()

    def getSubFlow(expectedKey: Int): Source[Int, _] = {
      masterSubscription.request(1)
      expectSubFlow(expectedKey)
    }

    def expectSubFlow(expectedKey: Int): Source[Int, _] = {
      val (key, substream) = masterSubscriber.expectNext()
      key should be(expectedKey)
      substream
    }

  }

  "groupBy" must {
    "work in the happy case" in assertAllStagesStopped {
      new SubstreamsSupport(groupCount = 2) {
        val s1 = StreamPuppet(getSubFlow(1).runWith(Sink.publisher(false)))
        masterSubscriber.expectNoMsg(100.millis)

        s1.expectNoMsg(100.millis)
        s1.request(1)
        s1.expectNext(1)
        s1.expectNoMsg(100.millis)

        val s2 = StreamPuppet(getSubFlow(0).runWith(Sink.publisher(false)))

        s2.expectNoMsg(100.millis)
        s2.request(2)
        s2.expectNext(2)

        // Important to request here on the OTHER stream because the buffer space is exactly one without the fanout box
        s1.request(1)
        s2.expectNext(4)

        s2.expectNoMsg(100.millis)

        s1.expectNext(3)

        s2.request(1)
        // Important to request here on the OTHER stream because the buffer space is exactly one without the fanout box
        s1.request(1)
        s2.expectNext(6)
        s2.expectComplete()

        s1.expectNext(5)
        s1.expectComplete()

        masterSubscriber.expectComplete()
      }
    }

    "accept cancellation of substreams" in assertAllStagesStopped {
      new SubstreamsSupport(groupCount = 2) {
        StreamPuppet(getSubFlow(1).runWith(Sink.publisher(false))).cancel()

        val substream = StreamPuppet(getSubFlow(0).runWith(Sink.publisher(false)))
        substream.request(2)
        substream.expectNext(2)
        substream.expectNext(4)
        substream.expectNoMsg(100.millis)

        substream.request(2)
        substream.expectNext(6)
        substream.expectComplete()

        masterSubscriber.expectComplete()
      }
    }

    "accept cancellation of master stream when not consumed anything" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source(publisherProbeProbe).groupBy(_ % 2).runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()
      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.cancel()
      upstreamSubscription.expectCancellation()
    }

    "accept cancellation of master stream when substreams are open" in assertAllStagesStopped {
      new SubstreamsSupport(groupCount = 3, elementCount = 13) {
        val substream = StreamPuppet(getSubFlow(1).runWith(Sink.publisher(false)))

        substream.request(1)
        substream.expectNext(1)

        masterSubscription.cancel()
        masterSubscriber.expectNoMsg(100.millis)

        // Open substreams still work, others are discarded
        substream.request(4)
        substream.expectNext(4)
        substream.expectNext(7)
        substream.expectNext(10)
        substream.expectNext(13)
        substream.expectComplete()
      }
    }

    "work with empty input stream" in assertAllStagesStopped {
      val publisher = Source(List.empty[Int]).groupBy(_ % 2).runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      subscriber.expectSubscriptionAndComplete()
    }

    "abort on onError from upstream" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source(publisherProbeProbe).groupBy(_ % 2).runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      val e = TE("test")
      upstreamSubscription.sendError(e)

      subscriber.expectError(e)
    }

    "abort on onError from upstream when substreams are running" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source(publisherProbeProbe).groupBy(_ % 2).runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream) = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream.runWith(Sink.publisher(false)))

      substreamPuppet.request(1)
      substreamPuppet.expectNext(1)

      val e = TE("test")
      upstreamSubscription.sendError(e)

      substreamPuppet.expectError(e)
      subscriber.expectError(e)

    }

    "fail stream when groupBy function throws" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val exc = TE("test")
      val publisher = Source(publisherProbeProbe)
        .groupBy(elem ⇒ if (elem == 2) throw exc else elem % 2)
        .runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, Unit])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream) = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream.runWith(Sink.publisher(false)))

      substreamPuppet.request(1)
      substreamPuppet.expectNext(1)

      upstreamSubscription.sendNext(2)

      subscriber.expectError(exc)
      substreamPuppet.expectError(exc)
      upstreamSubscription.expectCancellation()
    }

    "resume stream when groupBy function throws" in {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val exc = TE("test")
      val publisher = Source(publisherProbeProbe)
        .groupBy(elem ⇒ if (elem == 2) throw exc else elem % 2)
        .withAttributes(ActorAttributes.supervisionStrategy(resumingDecider))
        .runWith(Sink.publisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, Unit])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream1) = subscriber.expectNext()
      val substreamPuppet1 = StreamPuppet(substream1.runWith(Sink.publisher(false)))
      substreamPuppet1.request(10)
      substreamPuppet1.expectNext(1)

      upstreamSubscription.sendNext(2)
      upstreamSubscription.sendNext(4)

      val (_, substream2) = subscriber.expectNext()
      val substreamPuppet2 = StreamPuppet(substream2.runWith(Sink.publisher(false)))
      substreamPuppet2.request(10)
      substreamPuppet2.expectNext(4) // note that 2 was dropped

      upstreamSubscription.sendNext(3)
      substreamPuppet1.expectNext(3)

      upstreamSubscription.sendNext(6)
      substreamPuppet2.expectNext(6)

      upstreamSubscription.sendComplete()
      subscriber.expectComplete()
      substreamPuppet1.expectComplete()
      substreamPuppet2.expectComplete()
    }

    "pass along early cancellation" in assertAllStagesStopped {
      val up = TestPublisher.manualProbe[Int]()
      val down = TestSubscriber.manualProbe[(Int, Source[Int, Unit])]()

      val flowSubscriber = Source.subscriber[Int].groupBy(_ % 2).to(Sink(down)).run()

      val downstream = down.expectSubscription()
      downstream.cancel()
      up.subscribe(flowSubscriber)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

  }

}
