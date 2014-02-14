package akka.streams.impl.ops

import org.scalatest.{ ShouldMatchers, FreeSpec }
import akka.streams.impl._
import akka.streams.Operation.{ SingletonSource, Span }
import akka.streams.impl.BasicEffects.{ CompleteSink, HandleNextInSink }

class SpanImplSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "SpanImpl should" - {
    "produce stream of sources" in {
      object DownstreamSink extends NoOpSink[Any]
      val downstream = BasicEffects.forSink(DownstreamSink)
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      val HandleNextInSink(DownstreamSink, InternalSource(h1: (Downstream[Int] ⇒ (SyncSource, Effect)))) = p.handleNext(1)

      case object S1Downstream extends NoOpSink[Any]
      val (s1, Continue) = h1(BasicEffects.forSink[Int](S1Downstream))

      s1.handleRequestMore(1) should be(HandleNextInSink(S1Downstream, 1))
      s1.handleRequestMore(2) should be(UpstreamRequestMore(1))
      p.handleNext(2) should be(HandleNextInSink(S1Downstream, 2) ~ UpstreamRequestMore(1))
      p.handleNext(3) should be(HandleNextInSink(S1Downstream, 3) ~ CompleteSink(S1Downstream))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      val HandleNextInSink(DownstreamSink, InternalSource(h2: (Downstream[Int] ⇒ (SyncSource, Effect)))) = p.handleNext(4)

      case object S2Downstream extends NoOpSink[Any]
      val (s2, Continue) = h2(BasicEffects.forSink[Int](S2Downstream))
      s2.handleRequestMore(1) should be(HandleNextInSink(S2Downstream, 4))
      s2.handleRequestMore(1) should be(UpstreamRequestMore(1))
      p.handleNext(5) should be(HandleNextInSink(S2Downstream, 5))
      p.handleComplete() should be(CompleteSink(S2Downstream) ~ CompleteSink(DownstreamSink))
    }
    "return singleton source when first element of span matches" - {
      "in first span" in {
        object DownstreamSink extends NoOpSink[Any]
        val downstream = BasicEffects.forSink(DownstreamSink)
        val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

        p.handleRequestMore(1) should be(UpstreamRequestMore(1))
        p.handleNext(3) should be(HandleNextInSink(DownstreamSink, SingletonSource(3)))
      }
      "in consecutive spans" in {
        object DownstreamSink extends NoOpSink[Any]
        val downstream = BasicEffects.forSink(DownstreamSink)
        val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

        p.handleRequestMore(1) should be(UpstreamRequestMore(1))
        val HandleNextInSink(DownstreamSink, InternalSource(h1: (Downstream[Int] ⇒ (SyncSource, Effect)))) = p.handleNext(1)

        case object S1Downstream extends NoOpSink[Any]
        val (s1, Continue) = h1(BasicEffects.forSink[Int](S1Downstream))

        s1.handleRequestMore(1) should be(HandleNextInSink(S1Downstream, 1))
        s1.handleRequestMore(2) should be(UpstreamRequestMore(1))
        p.handleNext(2) should be(HandleNextInSink(S1Downstream, 2) ~ UpstreamRequestMore(1))
        p.handleNext(3) should be(HandleNextInSink(S1Downstream, 3) ~ CompleteSink(S1Downstream))

        p.handleRequestMore(1) should be(UpstreamRequestMore(1))
        p.handleNext(3) should be(HandleNextInSink(DownstreamSink, SingletonSource(3)))
      }
    }
    "upstream completes while nothing is requested" in {
      object DownstreamSink extends NoOpSink[Any]
      val downstream = BasicEffects.forSink(DownstreamSink)
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleComplete() should be(downstream.complete)
    }
    "upstream completes while waiting for first element" in {
      object DownstreamSink extends NoOpSink[Any]
      val downstream = BasicEffects.forSink(DownstreamSink)
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      p.handleComplete() should be(downstream.complete)
    }
    "upstream completes while waiting for sub-subscription" in {
      object DownstreamSink extends NoOpSink[Any]
      val downstream = BasicEffects.forSink(DownstreamSink)
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      val HandleNextInSink(DownstreamSink, InternalSource(h1: (Downstream[Int] ⇒ (SyncSource, Effect)))) = p.handleNext(1)

      p.handleComplete() should be(downstream.complete)

      case object S1Downstream extends NoOpSink[Any]
      val (s1, Continue) = h1(BasicEffects.forSink[Int](S1Downstream))
      s1.handleRequestMore(1) should be(HandleNextInSink(S1Downstream, 1) ~ CompleteSink(S1Downstream))
    }
    // test errors and cancellation
    // test behavior after completion / error
  }
}
