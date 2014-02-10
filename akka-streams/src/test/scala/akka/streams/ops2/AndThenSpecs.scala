package akka.streams
package ops2

import org.scalatest.{ ShouldMatchers, FreeSpec }
import Operation._

class AndThenSpecs extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "AndThenImpl in simple cases" - {
    "let elements flow forward" in {
      val combination = AndThenImpl.implementation[String, Float](upstream, downstream, null, Map((_: String) ⇒ 42).map(_.toFloat + 1.3f))
      val step @ AndThenImpl.NextToRight(_, 42) = combination.handleNext("test")
      step.runOne() should be(DownstreamNext(43.3f))
    }
  }
}
