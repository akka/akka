/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit._
import org.reactivestreams.Publisher

class FlowGroupBySpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  case class StreamPuppet(p: Publisher[Int]) {
    val probe = StreamTestKit.SubscriberProbe[Int]()
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
    val source = Source(1 to elementCount).runWith(Sink.publisher)
    val groupStream = Source(source).groupBy(_ % groupCount).runWith(Sink.publisher)
    val masterSubscriber = StreamTestKit.SubscriberProbe[(Int, Source[Int])]()

    groupStream.subscribe(masterSubscriber)
    val masterSubscription = masterSubscriber.expectSubscription()

    def getSubFlow(expectedKey: Int): Source[Int] = {
      masterSubscription.request(1)
      expectSubFlow(expectedKey)
    }

    def expectSubFlow(expectedKey: Int): Source[Int] = {
      val (key, substream) = masterSubscriber.expectNext()
      key should be(expectedKey)
      substream
    }

  }

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  "groupBy" must {
    "work in the happy case" in new SubstreamsSupport(groupCount = 2) {
      val s1 = StreamPuppet(getSubFlow(1).runWith(Sink.publisher))
      masterSubscriber.expectNoMsg(100.millis)

      s1.expectNoMsg(100.millis)
      s1.request(1)
      s1.expectNext(1)
      s1.expectNoMsg(100.millis)

      val s2 = StreamPuppet(getSubFlow(0).runWith(Sink.publisher))

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

    "accept cancellation of substreams" in new SubstreamsSupport(groupCount = 2) {
      StreamPuppet(getSubFlow(1).runWith(Sink.publisher)).cancel()

      val substream = StreamPuppet(getSubFlow(0).runWith(Sink.publisher))
      substream.request(2)
      substream.expectNext(2)
      substream.expectNext(4)
      substream.expectNoMsg(100.millis)

      substream.request(2)
      substream.expectNext(6)
      substream.expectComplete()

      masterSubscriber.expectComplete()

    }

    "accept cancellation of master stream when not consumed anything" in {
      val publisherProbeProbe = StreamTestKit.PublisherProbe[Int]()
      val publisher = Source(publisherProbeProbe).groupBy(_ % 2).runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Source[Int])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()
      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.cancel()
      upstreamSubscription.expectCancellation()
    }

    "accept cancellation of master stream when substreams are open" in new SubstreamsSupport(groupCount = 3, elementCount = 13) {
      val substream = StreamPuppet(getSubFlow(1).runWith(Sink.publisher))

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

    "work with empty input stream" in {
      val publisher = Source(List.empty[Int]).groupBy(_ % 2).runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Source[Int])]()
      publisher.subscribe(subscriber)

      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "abort on onError from upstream" in {
      val publisherProbeProbe = StreamTestKit.PublisherProbe[Int]()
      val publisher = Source(publisherProbeProbe).groupBy(_ % 2).runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Source[Int])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      val e = TE("test")
      upstreamSubscription.sendError(e)

      subscriber.expectError(e)
    }

    "abort on onError from upstream when substreams are running" in {
      val publisherProbeProbe = StreamTestKit.PublisherProbe[Int]()
      val publisher = Source(publisherProbeProbe).groupBy(_ % 2).runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Source[Int])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream) = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream.runWith(Sink.publisher))

      substreamPuppet.request(1)
      substreamPuppet.expectNext(1)

      val e = TE("test")
      upstreamSubscription.sendError(e)

      substreamPuppet.expectError(e)
      subscriber.expectError(e)

    }
  }

}
