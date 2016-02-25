/**
 * Copyright (C-2015 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream._
import akka.stream.Supervision.resumingDecider
import akka.stream.impl.SubscriptionTimeoutException
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import org.reactivestreams.Publisher
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.testkit.AkkaSpec

class FlowSplitWhenSpec extends AkkaSpec {
  import FlowSplitAfterSpec._

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withSubscriptionTimeoutSettings(StreamSubscriptionTimeoutSettings(StreamSubscriptionTimeoutTerminationMode.cancel, 1.second))

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

  class SubstreamsSupport(
    splitWhen: Int = 3,
    elementCount: Int = 6,
    substreamCancelStrategy: SubstreamCancelStrategy = SubstreamCancelStrategy.drain) {

    val source = Source(1 to elementCount)
    val groupStream = source.splitWhen(substreamCancelStrategy)(_ == splitWhen).lift.runWith(Sink.asPublisher(false))
    val masterSubscriber = TestSubscriber.manualProbe[Source[Int, _]]()

    groupStream.subscribe(masterSubscriber)
    val masterSubscription = masterSubscriber.expectSubscription()

    def getSubFlow(): Source[Int, _] = {
      masterSubscription.request(1)
      expectSubPublisher()
    }

    def expectSubPublisher(): Source[Int, _] = {
      val substream = masterSubscriber.expectNext()
      substream
    }

  }

  "splitWhen" must {

    "work in the happy case" in assertAllStagesStopped {
      new SubstreamsSupport(elementCount = 4) {
        val s1 = StreamPuppet(getSubFlow().runWith(Sink.asPublisher(false)))
        masterSubscriber.expectNoMsg(100.millis)

        s1.request(2)
        s1.expectNext(1)
        s1.expectNext(2)
        s1.request(1)
        s1.expectComplete()

        val s2 = StreamPuppet(getSubFlow().runWith(Sink.asPublisher(false)))

        s2.request(1)
        s2.expectNext(3)
        s2.expectNoMsg(100.millis)

        s2.request(1)
        s2.expectNext(4)
        s2.request(1)
        s2.expectComplete()

        masterSubscription.request(1)
        masterSubscriber.expectComplete()
      }
    }

    "not emit substreams if the parent stream is empty" in assertAllStagesStopped {

      Await.result(
        Source.empty[Int]
          .splitWhen(_ ⇒ true).lift
          .mapAsync(1)(_.runWith(Sink.headOption)).grouped(10).runWith(Sink.headOption),
        3.seconds) should ===(None) // rather tricky way of saying that no empty substream should be emitted (vs.  Some(None))

    }

    "work when first element is split-by" in assertAllStagesStopped {
      new SubstreamsSupport(1, elementCount = 3) {
        val s1 = StreamPuppet(getSubFlow().runWith(Sink.asPublisher(false)))

        s1.request(5)
        s1.expectNext(1)
        s1.expectNext(2)
        s1.expectNext(3)
        s1.expectComplete()

        masterSubscription.request(1)
        masterSubscriber.expectComplete()
      }
    }

    "support cancelling substreams" in assertAllStagesStopped {
      new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
        val s1 = StreamPuppet(getSubFlow().runWith(Sink.asPublisher(false)))
        s1.cancel()
        val s2 = StreamPuppet(getSubFlow().runWith(Sink.asPublisher(false)))

        s2.request(4)
        s2.expectNext(5)
        s2.expectNext(6)
        s2.expectNext(7)
        s2.expectNext(8)
        s2.request(1)
        s2.expectComplete()

        masterSubscription.request(1)
        masterSubscriber.expectComplete()
      }
    }

    "support cancelling both master and substream" in assertAllStagesStopped {
      val inputs = TestPublisher.probe[Int]()

      val substream = TestSubscriber.probe[Int]()
      val masterStream = TestSubscriber.probe[Any]()

      Source.fromPublisher(inputs)
        .splitWhen(_ == 2)
        .lift
        .map(_.runWith(Sink.fromSubscriber(substream)))
        .runWith(Sink.fromSubscriber(masterStream))

      masterStream.request(1)
      inputs.sendNext(1)

      substream.cancel()

      masterStream.expectNext(NotUsed)
      masterStream.expectNoMsg(100.millis)
      masterStream.cancel()
      inputs.expectCancellation()

      val inputs2 = TestPublisher.probe[Int]()
      Source.fromPublisher(inputs2)
        .splitWhen(_ == 2)
        .lift
        .map(_.runWith(Sink.cancelled))
        .runWith(Sink.cancelled)

      inputs2.expectCancellation()

      val inputs3 = TestPublisher.probe[Int]()

      val substream3 = TestSubscriber.probe[Int]()
      val masterStream3 = TestSubscriber.probe[Source[Int, Any]]()

      Source.fromPublisher(inputs3)
        .splitWhen(_ == 2)
        .lift
        .runWith(Sink.fromSubscriber(masterStream3))

      masterStream3.request(1)
      inputs3.sendNext(1)

      val src = masterStream3.expectNext()
      src.runWith(Sink.cancelled)

      masterStream3.request(1)
      inputs3.sendNext(2)
      val src2 = masterStream3.expectNext()
      val substream4 = TestSubscriber.probe[Int]()
      src2.runWith(Sink.fromSubscriber(substream4))

      substream4.requestNext(2)
      substream4.expectNoMsg(100.millis)
      masterStream3.expectNoMsg(100.millis)
      inputs3.expectRequest()
      inputs3.expectRequest()
      inputs3.expectNoMsg(100.millis)

      substream4.cancel()
      inputs3.expectNoMsg(100.millis)
      masterStream3.expectNoMsg(100.millis)

      masterStream3.cancel()
      inputs3.expectCancellation()

    }

    "support cancelling the master stream" in assertAllStagesStopped {
      new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
        val s1 = StreamPuppet(getSubFlow().runWith(Sink.asPublisher(false)))
        masterSubscription.cancel()
        s1.request(4)
        s1.expectNext(1)
        s1.expectNext(2)
        s1.expectNext(3)
        s1.expectNext(4)
        s1.request(1)
        s1.expectComplete()
      }
    }

    "fail stream when splitWhen function throws" in assertAllStagesStopped {
      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val exc = TE("test")
      val publisher = Source.fromPublisher(publisherProbeProbe)
        .splitWhen(elem ⇒ if (elem == 3) throw exc else elem % 3 == 0)
        .lift
        .runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[Source[Int, NotUsed]]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val substream = subscriber.expectNext()
      val substreamPuppet = StreamPuppet(substream.runWith(Sink.asPublisher(false)))

      substreamPuppet.request(10)
      substreamPuppet.expectNext(1)

      upstreamSubscription.sendNext(2)
      substreamPuppet.expectNext(2)

      upstreamSubscription.sendNext(3)

      subscriber.expectError(exc)
      substreamPuppet.expectError(exc)
      upstreamSubscription.expectCancellation()
    }

    "work with single elem splits" in assertAllStagesStopped {
      Await.result(
        Source(1 to 100).splitWhen(_ ⇒ true).lift
          .mapAsync(1)(_.runWith(Sink.head)) // Please note that this line *also* implicitly asserts nonempty substreams
          .grouped(200).runWith(Sink.head),
        3.second) should ===(1 to 100)
    }

    "fail substream if materialized twice" in assertAllStagesStopped {
      an[IllegalStateException] mustBe thrownBy {
        Await.result(
          Source.single(1).splitWhen(_ ⇒ true).lift
            .mapAsync(1) { src ⇒ src.runWith(Sink.ignore); src.runWith(Sink.ignore) } // Sink.ignore+mapAsync pipes error back
            .runWith(Sink.ignore),
          3.seconds)
      }
    }

    "fail stream if substream not materialized in time" in assertAllStagesStopped {
      val tightTimeoutMaterializer =
        ActorMaterializer(ActorMaterializerSettings(system)
          .withSubscriptionTimeoutSettings(
            StreamSubscriptionTimeoutSettings(StreamSubscriptionTimeoutTerminationMode.cancel, 500.millisecond)))

      val testSource = Source.single(1).concat(Source.maybe).splitWhen(_ ⇒ true)

      a[SubscriptionTimeoutException] mustBe thrownBy {
        Await.result(
          testSource.lift
            .delay(1.second)
            .flatMapConcat(identity)
            .runWith(Sink.ignore)(tightTimeoutMaterializer),
          3.seconds)
      }
    }

    "resume stream when splitWhen function throws" in assertAllStagesStopped {
      info("Supervision is not supported fully by GraphStages yet")
      pending

      val publisherProbeProbe = TestPublisher.manualProbe[Int]()
      val exc = TE("test")
      val publisher = Source.fromPublisher(publisherProbeProbe)
        .splitWhen(elem ⇒ if (elem == 3) throw exc else elem % 3 == 0)
        .lift
        .withAttributes(ActorAttributes.supervisionStrategy(resumingDecider))
        .runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[Source[Int, NotUsed]]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbeProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val substream1 = subscriber.expectNext()
      val substreamPuppet1 = StreamPuppet(substream1.runWith(Sink.asPublisher(false)))

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
      substreamPuppet1.expectComplete()
      val substream2 = subscriber.expectNext()
      val substreamPuppet2 = StreamPuppet(substream2.runWith(Sink.asPublisher(false)))
      substreamPuppet2.request(10)
      substreamPuppet2.expectNext(6)

      upstreamSubscription.sendComplete()
      subscriber.expectComplete()
      substreamPuppet2.expectComplete()
    }

    "pass along early cancellation" in assertAllStagesStopped {
      val up = TestPublisher.manualProbe[Int]()
      val down = TestSubscriber.manualProbe[Source[Int, NotUsed]]()

      val flowSubscriber = Source.asSubscriber[Int].splitWhen(_ % 3 == 0).lift.to(Sink.fromSubscriber(down)).run()

      val downstream = down.expectSubscription()
      downstream.cancel()
      up.subscribe(flowSubscriber)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

    "support eager cancellation of master stream on cancelling substreams" in assertAllStagesStopped {
      new SubstreamsSupport(splitWhen = 5, elementCount = 8, SubstreamCancelStrategy.propagate) {
        val s1 = StreamPuppet(getSubFlow().runWith(Sink.asPublisher(false)))
        s1.cancel()
        masterSubscriber.expectComplete()
      }
    }

  }

}
