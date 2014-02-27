package akka.streams.impl.ops

import org.scalatest.{ FreeSpec, ShouldMatchers }
import akka.streams.impl.{ OperationImpl, SyncOperation, SyncOperationSpec }
import akka.streams.Operation.TakeWhile

class TakeWhileImplSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "TakeWhileImpl should" - {
    // FIXME: these tests seem to need a dedicated implementation to provide the best backpressure propagation
    "leave elements through while the predicate matches and complete and cancel afterwards" in pendingUntilFixed {
      val impl = implementation[Int](_ % 10 == 0)
      impl.handleRequestMore(1) should be(UpstreamRequestMore(1))
      impl.handleNext(5) should be(DownstreamNext(5))

      impl.handleRequestMore(1) should be(UpstreamRequestMore(1))
      impl.handleNext(12) should be(DownstreamNext(12))

      impl.handleRequestMore(1) should be(UpstreamRequestMore(1))
      impl.handleNext(10) should be(DownstreamComplete ~ UpstreamCancel)
    }
    "work on empty input" in {
      val impl = implementation[Int](_ % 10 == 0)
      impl.handleComplete() should be(DownstreamComplete)
    }
    "don't change requestMore" in pendingUntilFixed {
      val impl = implementation[Int](_ % 10 == 0)
      impl.handleRequestMore(5) should be(UpstreamRequestMore(5))
      impl.handleNext(5) should be(DownstreamNext(5))
      impl.handleNext(10) should be(DownstreamComplete ~ UpstreamCancel)
    }
  }

  def implementation[T](p: T ⇒ Boolean): SyncOperation[T] =
    OperationImpl(upstream, downstream, TestContextEffects, TakeWhile(p))
}
