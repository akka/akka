package akka.streams
package impl

import org.scalatest.{ ShouldMatchers, FreeSpec }
import Operation._

class ComposeImplSpecs extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "AndThenImpl in simple cases" - {
    "let elements flow forward" in {
      val combination = OperationImpl[String, Float](upstream, downstream, null, Map((_: String) ⇒ 42).map(_.toFloat + 1.3f))
      val step @ ComposeImpl.NextToRight(_, 42) = combination.handleNext("test")
      step.runOne() should be(DownstreamNext(43.3f))
    }
    "let requests flow backwards" in pending
    "allow back-and-forth chatter without SO" in pending
    "wire internal publishers correctly" in pending
    "wire internal subscribers correctly" in pending
    "link internal publishers and subscribers" in pending
    "handle deeply nested combined results without SO" in pending
  }
  "AndThenImpl in deeper nested cases" - {
    "wire internal publishers correctly" in pending
    "wire internal subscribers correctly" in pending
  }
}
