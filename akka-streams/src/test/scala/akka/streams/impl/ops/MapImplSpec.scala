package akka.streams.impl.ops

import org.scalatest.{ FreeSpec, ShouldMatchers }

import akka.streams.impl._

class MapImplSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  def add23(i: Int): Int = i + 23
  def bias42(i: Int): Int = if (i != 42) throw TestException else 42
  def newImpl() = new MapImpl(upstream, downstream, add23)

  "MapImpl" - {
    "propagate requestMore in a 1 to 1 fashion" in {
      val impl = newImpl()
      impl.handleRequestMore(14) should be(UpstreamRequestMore(14))
      impl.handleRequestMore(12) should be(UpstreamRequestMore(12))
    }
    "propagate cancel" in {
      val impl = newImpl()
      impl.handleCancel() should be(UpstreamCancel)
      // after upstream cancel nothing should ever be received from upstream
    }
    "propagate complete" in {
      val impl = newImpl()
      impl.handleComplete() should be(DownstreamComplete)
      expectIllegalState(impl)
    }
    "propagate error" in {
      val impl = newImpl()
      impl.handleError(TestException) should be(DownstreamError(TestException))
      expectIllegalState(impl)
    }
    "map using the user function onNext element" in {
      val impl = newImpl()
      impl.handleNext(42) should be(DownstreamNext(65))
      impl.handleNext(23) should be(DownstreamNext(46))
    }
    "when user function throws an error" - {
      "propagate as onError and cancel upstream" in {
        val impl = new MapImpl(upstream, downstream, bias42)
        impl.handleNext(42) should be(DownstreamNext(42))
        impl.handleNext(38) should be(DownstreamError(TestException) ~ UpstreamCancel)
        expectIllegalState(impl)
      }
    }
  }

  def expectIllegalState(impl: SyncOperation[Int]): Unit = {
    intercept[IllegalStateException](impl.handleRequestMore(12))
    intercept[IllegalStateException](impl.handleNext(12))
  }
}
