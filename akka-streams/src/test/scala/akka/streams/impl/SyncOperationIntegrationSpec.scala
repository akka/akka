package akka.streams.impl

import scala.language.postfixOps

import org.scalatest.{ ShouldMatchers, FreeSpec }

import akka.streams._
import Operation._

class SyncOperationIntegrationSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "Simple chains" - {}
  "Complex chains requiring back-forth chatter" - {
    "internal source + map + fold" in {
      val combination = instance(FromIterableSource(1 to 10).map(_ + 1).fold(0f)(_ + _.toFloat))
      val r @ BasicEffects.RequestMoreFromSource(_, 100) = combination.handleRequestMore(1)
      r.runToResult() should be(DownstreamNext(65.0) ~ DownstreamComplete)
    }
    "flatten with generic producer" in pending
    "flatten.map(_ + 1f)" in {
      val impl = instance(Flatten[Float]().map(_ + 1f))
      pending
    }
    "span + flatten == identity" in {
      val p = instance[Int](FromIterableSource(1 to 6).span(_ % 3 == 0).flatten)
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(2))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(3))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(4))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(5))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(6) ~ DownstreamComplete)
    }
    "map value to source and then flatten" in {
      def f(i: Int): Source[Int] = Seq(999).toSource ++ (1 to i)
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
