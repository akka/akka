package akka.streams.impl.ops

import org.scalatest.{ FreeSpec, ShouldMatchers }
import akka.streams.impl._
import akka.streams.Operation.Source
import rx.async.spi.Subscription

class SourceHeadTailImplSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  class MockDownstream[I] extends Downstream[I] {
    val internalSubscriber = new NoOpSubscriber[I] {}
    val effects = BasicEffects.forSubscriber(internalSubscriber)
    val complete = effects.complete
    override val error: (Throwable) ⇒ Effect = effects.error
    override val next: (I) ⇒ Effect = effects.next
  }
  class MockUpstream extends Upstream {
    val internalSubscription = new Subscription {
      override def requestMore(elements: Int): Unit = ???
      override def cancel(): Unit = ???
    }
    val effects = BasicEffects.forSubscription(internalSubscription)
    override val requestMore: (Int) ⇒ Effect = effects.requestMore
    override val cancel: Effect = effects.cancel
  }

  val S1Downstream = new MockDownstream[Int]
  val S2Downstream = new MockDownstream[Int]
  val UpstreamConsPlaceholder: Downstream[Int] ⇒ SyncSource = _ ⇒ ???

  "SourceHeadTailImpl should" - {
    "complete downstream immediately when upstream is already completed" in {
      val impl = implementation()
      impl.handleComplete() should be(DownstreamComplete)
    }
    "when substreams have elements immediately available" - {
      "work for one substream" in {
        val impl = implementation()
        impl.handleRequestMore(1) should be(UpstreamRequestMore(1))
        val prelimResult = impl.handleNext(Seq(1, 2, 3, 4))

        // complete during subscription
        impl.handleComplete() should be(Continue)

        val Effects(Vector(DownstreamNext((1, internal: Source[Int] @unchecked)), DownstreamComplete)) = prelimResult.runToResult()
        val handler = internal.expectInternalSourceHandler()

        val (s1, Continue) = handler(S1Downstream)
        s1.handleRequestMore(2).runToResult() should be(S1Downstream.next(2) ~ S1Downstream.next(3))
        s1.handleRequestMore(2).runToResult() should be(S1Downstream.next(4) ~ S1Downstream.complete)
      }
      "work for several requested substreams" in {
        val impl = implementation()
        impl.handleRequestMore(2) should be(UpstreamRequestMore(1))

        val Effects(Vector(downstreamNext, UpstreamRequestMore(1))) = impl.handleNext(Seq(1, 2, 3, 4)).runToResult()
        val (1, internal) = downstreamNext.expectDownstreamNext[(Int, Source[Int])]()
        val handler = internal.expectInternalSourceHandler()

        val (5, internal2) = impl.handleNext(Seq(5, 6, 7)).runToResult().expectDownstreamNext[(Int, Source[Int])]()
        val handler2 = internal2.expectInternalSourceHandler()

        val (s1, Continue) = handler(S1Downstream)
        s1.handleRequestMore(2).runToResult() should be(S1Downstream.next(2) ~ S1Downstream.next(3))

        val (s2, Continue) = handler2(S2Downstream)
        s2.handleRequestMore(2).runToResult() should be(S2Downstream.next(6) ~ S2Downstream.next(7) ~ S2Downstream.complete)
        s1.handleRequestMore(2).runToResult() should be(S1Downstream.next(4) ~ S1Downstream.complete)
      }
    }
    "when substreams are lazy" - {
      "with several substreams" in {
        val impl = implementation()
        impl.handleRequestMore(2) should be(UpstreamRequestMore(1))

        val s1Source = TestContextEffects.internalProducer[Int](UpstreamConsPlaceholder)
        val S1Upstream = new MockUpstream

        val ConnectInternalSourceSink(_, downstreamCons) = impl.handleNext(s1Source)
        val s1 = downstreamCons(S1Upstream)
        s1.start() should be(S1Upstream.requestMore(1))
        val Effects(Vector(downstreamNext, UpstreamRequestMore(1))) = s1.handleNext(42)
        val (42, internal) = downstreamNext.expectDownstreamNext[(Int, Source[Int])]()
        val handler1 = internal.expectInternalSourceHandler()

        val s2Source = TestContextEffects.internalProducer[Int](UpstreamConsPlaceholder)
        val S2Upstream = new MockUpstream

        val (d1, Continue) = handler1(S1Downstream)

        val ConnectInternalSourceSink(_, downstreamCons2) = impl.handleNext(s2Source)
        val s2 = downstreamCons(S2Upstream)
        s2.start() should be(S2Upstream.requestMore(1))

        d1.handleRequestMore(3) should be(S1Upstream.requestMore(3))
        s1.handleNext(38) should be(S1Downstream.next(38))

        val (199, internal2) = s2.handleNext(199).expectDownstreamNext[(Int, Source[Int])]()
        val handler2 = internal2.expectInternalSourceHandler()
        val (d2, Continue) = handler2(S2Downstream)
        d2.handleRequestMore(1) should be(S2Upstream.requestMore(1))

        s1.handleNext(999) should be(S1Downstream.next(999))
        s2.handleNext(555) should be(S2Downstream.next(555))
        s1.handleComplete() should be(S1Downstream.complete)
      }
      "work for one substream when substream is closed during internal wiring" in {
        val impl = implementation()
        impl.handleRequestMore(2) should be(UpstreamRequestMore(1))

        val UpstreamConsPlaceholder: Downstream[Int] ⇒ SyncSource = _ ⇒ ???
        val s1Source = TestContextEffects.internalProducer[Int](UpstreamConsPlaceholder)
        val S1Upstream = new MockUpstream

        val ConnectInternalSourceSink(_, downstreamCons) = impl.handleNext(s1Source)
        val s1 = downstreamCons(S1Upstream)
        s1.start() should be(S1Upstream.requestMore(1))
        val Effects(Vector(downstreamNext, UpstreamRequestMore(1))) = s1.handleNext(42)
        s1.handleComplete() should be(Continue)

        val (42, internal) = downstreamNext.expectDownstreamNext[(Int, Source[Int])]()
        val handler1 = internal.expectInternalSourceHandler()
        val (d1, S1Downstream.complete) = handler1(S1Downstream)
      }
    }
    "when a substream has no elements" in pending
  }

  def implementation() = new SourceHeadTailImpl[Int](upstream, downstream, TestContextEffects)
}
