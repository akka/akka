package akka.streams.impl.ops

import org.scalatest.{ FreeSpec, ShouldMatchers }

import akka.streams.impl._
import scala.concurrent.{ Future, Promise }
import scala.util.{ Success, Failure }

class FromFutureSourceImplSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "FromFutureSource should" - {
    "if already finished when starting" - {
      "wait for delivering value until requestMore" in {
        val future = Future.successful(28)
        val impl = new FromFutureSourceImpl(downstream, TestContextEffects, future)
        impl.start() should be(Continue)
        impl.handleRequestMore(1) should be(DownstreamNext(28) ~ DownstreamComplete)
      }
      "return error immediately" in {
        val future = Future.failed(TestException)
        val impl = new FromFutureSourceImpl(downstream, TestContextEffects, future)
        impl.start() should be(DownstreamError(TestException))
      }
    }
    "if already finished when requesting" - {
      "should deliver result immediately" in {
        val future = Future.successful(28)
        val impl = new FromFutureSourceImpl(downstream, TestContextEffects, future)
        impl.handleRequestMore(1) should be(DownstreamNext(28) ~ DownstreamComplete)
      }
      "should deliver error immediately" in {
        val future = Future.failed(TestException)
        val impl = new FromFutureSourceImpl(downstream, TestContextEffects, future)
        impl.handleRequestMore(1) should be(DownstreamError(TestException))
      }
    }
    "if not yet finished" - {
      "on success deliver one element and then complete" in {
        val promise = Promise[String]()
        val impl = new FromFutureSourceImpl(downstream, TestContextEffects, promise.future)
        impl.handleRequestMore(1) should be(Continue)
        promise.complete(Success("test"))
        expectAndRunContextEffect() should be(DownstreamNext("test") ~ DownstreamComplete)
      }
      "on error propagate the error" in {
        val promise = Promise[String]()
        val impl = new FromFutureSourceImpl(downstream, TestContextEffects, promise.future)
        impl.handleRequestMore(1) should be(Continue)
        promise.complete(Failure(TestException))
        expectAndRunContextEffect() should be(DownstreamError(TestException))
      }
    }
    "if cancelled before requesting" in pending
    "if cancelled after requesting but before future is finished" in pending
  }
}
