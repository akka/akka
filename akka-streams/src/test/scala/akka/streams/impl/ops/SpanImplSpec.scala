package akka.streams.impl.ops

import org.scalatest.{ ShouldMatchers, FreeSpec }
import akka.streams.impl._
import akka.streams.Operation.{ Source, SingletonSource, Span }
import akka.streams.impl.BasicEffects.{ CompleteSink, HandleNextInSink }

class SpanImplSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  case object S1Downstream extends NoOpSink[Int]
  case object S2Downstream extends NoOpSink[Int]

  "SpanImpl should" - {
    "produce stream of sources" in {
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      val h1 = p.handleNext(1).expectDownstreamNext[Source[Int]]().expectInternalSourceHandler()

      val (s1, Continue) = h1(BasicEffects.forSink(S1Downstream))

      s1.handleRequestMore(1) should be(HandleNextInSink(S1Downstream, 1))
      s1.handleRequestMore(2) should be(UpstreamRequestMore(1))
      p.handleNext(2) should be(HandleNextInSink(S1Downstream, 2) ~ UpstreamRequestMore(1))
      p.handleNext(3) should be(HandleNextInSink(S1Downstream, 3) ~ CompleteSink(S1Downstream))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      val h2 = p.handleNext(4).expectDownstreamNext[Source[Int]]().expectInternalSourceHandler()

      val (s2, Continue) = h2(BasicEffects.forSink(S2Downstream))
      s2.handleRequestMore(1) should be(HandleNextInSink(S2Downstream, 4))
      s2.handleRequestMore(1) should be(UpstreamRequestMore(1))
      p.handleNext(5) should be(HandleNextInSink(S2Downstream, 5))
      p.handleComplete() should be(CompleteSink(S2Downstream) ~ DownstreamComplete)
    }
    "return singleton source when first element of span matches" - {
      "in first span" in {
        val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

        p.handleRequestMore(1) should be(UpstreamRequestMore(1))
        p.handleNext(3).expectDownstreamNext[Source[Int]]() should be(SingletonSource(3))
      }
      "in consecutive spans" in {
        val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

        p.handleRequestMore(1) should be(UpstreamRequestMore(1))
        val h1 = p.handleNext(1).expectDownstreamNext[Source[Int]]().expectInternalSourceHandler()

        val (s1, Continue) = h1(BasicEffects.forSink(S1Downstream))

        s1.handleRequestMore(1) should be(HandleNextInSink(S1Downstream, 1))
        s1.handleRequestMore(2) should be(UpstreamRequestMore(1))
        p.handleNext(2) should be(HandleNextInSink(S1Downstream, 2) ~ UpstreamRequestMore(1))
        p.handleNext(3) should be(HandleNextInSink(S1Downstream, 3) ~ CompleteSink(S1Downstream))

        p.handleRequestMore(1) should be(UpstreamRequestMore(1))
        p.handleNext(3).expectDownstreamNext[Source[Int]]() should be(SingletonSource(3))
      }
    }
    "upstream completes while nothing is requested" in {
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleComplete() should be(DownstreamComplete)
    }
    "upstream completes while waiting for first element" in {
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      p.handleComplete() should be(DownstreamComplete)
    }
    "upstream completes while waiting for sub-subscription" in {
      val p: SyncOperation[Int] = new SpanImpl[Int](upstream, downstream, Span[Int](_ % 3 == 0))

      p.handleRequestMore(1) should be(UpstreamRequestMore(1))
      val h1 = p.handleNext(1).expectDownstreamNext[Source[Int]]().expectInternalSourceHandler()

      p.handleComplete() should be(DownstreamComplete)

      val (s1, Continue) = h1(BasicEffects.forSink(S1Downstream))
      s1.handleRequestMore(1) should be(HandleNextInSink(S1Downstream, 1) ~ CompleteSink(S1Downstream))
    }
    // test errors and cancellation
    // test behavior after completion / error
  }
}
