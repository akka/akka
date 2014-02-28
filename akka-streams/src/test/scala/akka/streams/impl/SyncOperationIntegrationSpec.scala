package akka.streams.impl

import scala.language.postfixOps

import org.scalatest.{ ShouldMatchers, FreeSpec }

import akka.streams._
import Operation._
import scala.concurrent.{ Promise, Future }
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.Success

class SyncOperationIntegrationSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "Simple chains" - {}
  "Complex chains requiring back-forth chatter" - {
    "internal source + map + fold" in {
      val combination = instance(Source(1 to 10).map(_ + 1).fold(0f)(_ + _.toFloat))
      val r @ BasicEffects.RequestMoreFromSource(_, 100) = combination.handleRequestMore(1)
      r.runToResult() should be(DownstreamNext(65.0) ~ DownstreamComplete)
    }
    "flatten with generic producer" in pending
    "flatten.map(_ + 1f)" in {
      val impl = instance(Flatten[Float]().map(_ + 1f))
      pending
    }
    "mapAsync = flatMap with function returning future" in {
      import system.dispatcher
      def mapSlow(i: Int): Future[Int] = {
        // simulate a long-running complex addition
        val promise = Promise[Int]()
        system.scheduler.scheduleOnce(ThreadLocalRandom.current().nextInt(0, 100).millis) {
          promise.complete(Success(i + 1))
        }
        promise.future
      }
      val p = instance[Int](Source(1 to 6).flatMap(mapSlow))
      p.start().runToResult() should be(Continue)
      def requestAndExpectNextEffect(e: Effect): Unit =
        p.handleRequestMore(1).runToResult() match {
          case Continue ⇒ expectAndRunContextEffect().runToResult() should be(e)
          case x        ⇒ x should be(e)
        }
      def requestAndExpectNext(i: Int): Unit = requestAndExpectNextEffect(DownstreamNext(i))

      requestAndExpectNext(2)
      requestAndExpectNext(3)
      requestAndExpectNext(4)
      requestAndExpectNext(5)
      requestAndExpectNext(6)
      requestAndExpectNextEffect(DownstreamNext(7) ~ DownstreamComplete)
    }
    "span + flatten == identity" in {
      val p = instance[Int](Source(1 to 6).span(_ % 3 == 0).flatten)
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(2))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(3))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(4))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(5))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(6) ~ DownstreamComplete)
    }
    "map value to source and then flatten" in {
      def f(i: Int): Source[Int] = Source(999) ++ (1 to i)
      val impl = instance[Int]((1 to 5 toSource).flatMap(f))
      // TODO: exhausting a source seems like a pattern: simplify!
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(999))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(999))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(2))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(999))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(2))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(3))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(999))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(2))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(3))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(4))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(999))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(2))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(3))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(4))
      impl.handleRequestMore(1).runToResult() should be(DownstreamNext(5) ~ DownstreamComplete)
    }
  }

  def instance[O](source: Source[O]): SyncSource =
    OperationImpl(downstream, TestContextEffects, source)
  def instance[I](operation: Operation[I, Float]): SyncOperation[I] =
    OperationImpl(upstream, downstream, TestContextEffects, operation)
}
