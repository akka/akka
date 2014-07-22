/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit._
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Flow
import scala.util.control.NoStackTrace

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowGroupBySpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

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
    val source = Flow((1 to elementCount).iterator).toPublisher(materializer)
    val groupStream = Flow(source).groupBy(_ % groupCount).toPublisher(materializer)
    val masterSubscriber = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()

    groupStream.subscribe(masterSubscriber)
    val masterSubscription = masterSubscriber.expectSubscription()

    def getSubPublisher(expectedKey: Int): Publisher[Int] = {
      masterSubscription.request(1)
      expectSubPublisher(expectedKey: Int)
    }

    def expectSubPublisher(expectedKey: Int): Publisher[Int] = {
      val (key, substream) = masterSubscriber.expectNext()
      key should be(expectedKey)
      substream
    }

  }

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  "groupBy" must {
    "work in the happy case" in new SubstreamsSupport(groupCount = 2) {
      val s1 = StreamPuppet(getSubPublisher(1))
      masterSubscriber.expectNoMsg(100.millis)

      s1.expectNoMsg(100.millis)
      s1.request(1)
      s1.expectNext(1)
      s1.expectNoMsg(100.millis)

      val s2 = StreamPuppet(getSubPublisher(0))

      s2.expectNoMsg(100.millis)
      s2.request(2)
      s2.expectNext(2)
      s2.expectNext(4)

      s2.expectNoMsg(100.millis)

      s1.request(1)
      s1.expectNext(3)

      s2.request(1)
      s2.expectNext(6)
      s2.expectComplete()

      s1.request(1)
      s1.expectNext(5)
      s1.expectComplete()

      masterSubscriber.expectComplete()

    }

    "accept cancellation of substreams" in new SubstreamsSupport(groupCount = 2) {
      StreamPuppet(getSubPublisher(1)).cancel()

      val substream = StreamPuppet(getSubPublisher(0))
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
      val publisher = Flow(publisherProbeProbe).groupBy(_ % 2).toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()
      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.cancel()
      upstreamSubscription.expectCancellation()
    }

    "accept cancellation of master stream when substreams are open" in new SubstreamsSupport(groupCount = 3, elementCount = 13) {
      pending
      // FIXME: Needs handling of loose substreams that no one refers to anymore.
      //      val substream = StreamPuppet(getSubproducer(1))
      //
      //      substream.request(1)
      //      substream.expectNext(1)
      //
      //      masterSubscription.cancel()
      //      masterSubscriber.expectNoMsg(100.millis)
      //
      //      // Open substreams still work, others are discarded
      //      substream.request(4)
      //      substream.expectNext(4)
      //      substream.expectNext(7)
      //      substream.expectNext(10)
      //      substream.expectNext(13)
      //      substream.expectComplete()
    }

    "work with fanout on substreams" in new SubstreamsSupport(groupCount = 2) {
      val substreamPublisher = getSubPublisher(1)
      getSubPublisher(0)

      val substreamSubscriber1 = StreamPuppet(substreamPublisher)
      val substreamSubscriber2 = StreamPuppet(substreamPublisher)

      substreamSubscriber1.request(1)
      substreamSubscriber1.expectNext(1)
      substreamSubscriber2.request(1)
      substreamSubscriber2.expectNext(1)

      substreamSubscriber1.request(1)
      substreamSubscriber1.expectNext(3)
      substreamSubscriber2.request(1)
      substreamSubscriber2.expectNext(3)
    }

    "work with fanout on master stream" in {
      val source = Flow((1 to 4).iterator).toPublisher(materializer)
      val groupStream = Flow(source).groupBy(_ % 2).toPublisher(materializer)
      val masterSubscriber1 = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      val masterSubscriber2 = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()

      groupStream.subscribe(masterSubscriber1)
      groupStream.subscribe(masterSubscriber2)

      val masterSubscription1 = masterSubscriber1.expectSubscription()
      val masterSubscription2 = masterSubscriber2.expectSubscription()

      masterSubscription1.request(2)
      masterSubscription2.request(1)

      val (key11, substream11) = masterSubscriber1.expectNext()
      key11 should be(1)
      val (key21, substream21) = masterSubscriber2.expectNext()
      key21 should be(1)

      val puppet11 = StreamPuppet(substream11)
      val puppet21 = StreamPuppet(substream21)

      puppet11.request(2)
      puppet11.expectNext(1)
      puppet11.expectNext(3)

      puppet21.request(1)
      puppet21.expectNext(1)
      puppet21.cancel()

      masterSubscription2.cancel()

      val (key12, substream12) = masterSubscriber1.expectNext()
      key12 should be(0)

      val puppet12 = StreamPuppet(substream12)
      puppet12.request(1)
      puppet12.expectNext(2)
      puppet12.cancel()
      masterSubscription1.cancel()
    }

    "work with empty input stream" in {
      val publisher = Flow(List.empty[Int]).groupBy(_ % 2).toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      publisher.subscribe(subscriber)

      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "abort on onError from upstream" in {
      val publisherProbeProbe = StreamTestKit.PublisherProbe[Int]()
      val publisher = Flow(publisherProbeProbe).groupBy(_ % 2).toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
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
      val publisher = Flow(publisherProbeProbe).groupBy(_ % 2).toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream) = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream)

      substreamPuppet.request(1)
      substreamPuppet.expectNext(1)

      val e = TE("test")
      upstreamSubscription.sendError(e)

      substreamPuppet.expectError(e)
      subscriber.expectError(e)

    }
  }

}
