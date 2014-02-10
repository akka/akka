package akka.streams.ops2

import org.scalatest.{ ShouldMatchers, FreeSpec }
import akka.streams.Operation
import Operation._

class SyncOperationIntegrationSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "Simple chains" - {}
  "Complex chains requiring back-forth chatter" - {
    "internal source + map + fold" in {
      val combination = instance(FromIterableSource(1 to 10).map(_ + 1).fold(0f)(_ + _.toFloat))
      val r @ ComposeImpl.RequestMoreFromLeft(_, 100) = combination.handleRequestMore(1)
      r.runToResult() should be(DownstreamNext(65.0) ~ DownstreamComplete)
    }
    "flatten.map(_ + 1f)" in {
      val combination = instance(Flatten[Float]().map(_ + 1f))
      pending
    }
    "span + flatten == identity" in pending
  }

  def instance(source: Source[Float]): SyncSource =
    OperationImpl(downstream, null, source)
  def instance[I](operation: Operation[I, Float]): SyncOperation[I] =
    OperationImpl(upstream, downstream, null, operation)
}
