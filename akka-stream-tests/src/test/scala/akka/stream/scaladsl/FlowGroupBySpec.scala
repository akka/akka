/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import scala.concurrent.duration._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.Supervision.resumingDecider
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import org.reactivestreams.Publisher
import akka.stream.ActorAttributes
import org.scalatest.concurrent.ScalaFutures
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import akka.stream.testkit.scaladsl.TestSource
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.AkkaSpec

object FlowGroupBySpec {

  implicit class Lift[M](val f: SubFlow[Int, M, Source[Int, M]#Repr, RunnableGraph[M]]) extends AnyVal {
    def lift(key: Int ⇒ Int) = f.prefixAndTail(1).map(p ⇒ key(p._1.head) -> (Source.single(p._1.head) ++ p._2)).concatSubstreams
  }

}

class FlowGroupBySpec extends AkkaSpec {
  import FlowGroupBySpec._

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

  class SubstreamsSupport(groupCount: Int = 2, elementCount: Int = 6, maxSubstreams: Int = -1) {
    val source = Source(1 to elementCount).runWith(Sink.asPublisher(false))
    val max = if (maxSubstreams > 0) maxSubstreams else groupCount
    val groupStream = Source.fromPublisher(source).groupBy(max, _ % groupCount).lift(_ % groupCount).runWith(Sink.asPublisher(false))
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
        val s1 = StreamPuppet(getSubFlow(1).runWith(Sink.asPublisher(false)))
        masterSubscriber.expectNoMsg(100.millis)

        s1.expectNoMsg(100.millis)
        s1.request(1)
        s1.expectNext(1)
        s1.expectNoMsg(100.millis)

        val s2 = StreamPuppet(getSubFlow(0).runWith(Sink.asPublisher(false)))

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

        masterSubscription.request(1)
        masterSubscriber.expectComplete()
      }
    }

    "work in normal user scenario" in {
      Source(List("Aaa", "Abb", "Bcc", "Cdd", "Cee"))
        .groupBy(3, _.substring(0, 1))
        .grouped(10)
        .mergeSubstreams
        .grouped(10)
        .runWith(Sink.head)
        .futureValue(Timeout(3.seconds))
        .sortBy(_.head) should ===(List(List("Aaa", "Abb"), List("Bcc"), List("Cdd", "Cee")))
    }

    "accept cancellation of substreams" in assertAllStagesStopped {
      new SubstreamsSupport(groupCount = 2) {
        StreamPuppet(getSubFlow(1).runWith(Sink.asPublisher(false))).cancel()

        val substream = StreamPuppet(getSubFlow(0).runWith(Sink.asPublisher(false)))
        substream.request(2)
        substream.expectNext(2)
        substream.expectNext(4)
        substream.expectNoMsg(100.millis)

        substream.request(2)
        substream.expectNext(6)
        substream.expectComplete()

        masterSubscription.request(1)
        masterSubscriber.expectComplete()
      }
    }

    "accept cancellation of master stream when not consumed anything" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source.fromPublisher(publisherProbeProbe).groupBy(2, _ % 2).lift(_ % 2).runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()
      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.cancel()
      upstreamSubscription.expectCancellation()
    }

    "work with empty input stream" in assertAllStagesStopped {
      val publisher = Source(List.empty[Int]).groupBy(2, _ % 2).lift(_ % 2).runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      subscriber.expectSubscriptionAndComplete()
    }

    "abort on onError from upstream" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source.fromPublisher(publisherProbeProbe).groupBy(2, _ % 2).lift(_ % 2).runWith(Sink.asPublisher(false))
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
      val publisher = Source.fromPublisher(publisherProbeProbe).groupBy(2, _ % 2).lift(_ % 2).runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream) = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream.runWith(Sink.asPublisher(false)))

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
      val publisher = Source.fromPublisher(publisherProbeProbe)
        .groupBy(2, elem ⇒ if (elem == 2) throw exc else elem % 2)
        .lift(_ % 2)
        .runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, NotUsed])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream) = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream.runWith(Sink.asPublisher(false)))

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
      val publisher = Source.fromPublisher(publisherProbeProbe)
        .groupBy(2, elem ⇒ if (elem == 2) throw exc else elem % 2)
        .lift(_ % 2)
        .withAttributes(ActorAttributes.supervisionStrategy(resumingDecider))
        .runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, NotUsed])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream1) = subscriber.expectNext()
      val substreamPuppet1 = StreamPuppet(substream1.runWith(Sink.asPublisher(false)))
      substreamPuppet1.request(10)
      substreamPuppet1.expectNext(1)

      upstreamSubscription.sendNext(2)
      upstreamSubscription.sendNext(4)

      val (_, substream2) = subscriber.expectNext()
      val substreamPuppet2 = StreamPuppet(substream2.runWith(Sink.asPublisher(false)))
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
      val down = TestSubscriber.manualProbe[(Int, Source[Int, NotUsed])]()

      val flowSubscriber = Source.asSubscriber[Int].groupBy(2, _ % 2).lift(_ % 2).to(Sink.fromSubscriber(down)).run()

      val downstream = down.expectSubscription()
      downstream.cancel()
      up.subscribe(flowSubscriber)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

    "fail when exceeding maxSubstreams" in assertAllStagesStopped {
      val (up, down) = Flow[Int]
        .groupBy(1, _ % 2).prefixAndTail(0).mergeSubstreams
        .runWith(TestSource.probe[Int], TestSink.probe)

      down.request(2)

      up.sendNext(1)
      val first = down.expectNext()
      val s1 = StreamPuppet(first._2.runWith(Sink.asPublisher(false)))

      s1.request(1)
      s1.expectNext(1)

      up.sendNext(2)
      val ex = down.expectError()
      ex.getMessage should include("too many substreams")
      s1.expectError(ex)
    }

  }

}
