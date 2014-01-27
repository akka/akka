package akka.streams

import org.scalatest.{ ShouldMatchers, WordSpec }
import ProcessorActor._

class OperationSemanticsSpec extends WordSpec with ShouldMatchers {
  val oneToTen = Produce(1 to 10)
  import Operations._

  "Operations" should {
    "fold elements synchronously with small input" in {
      val p = instance(oneToTen.fold(0)(_ + _))
      p.requestMoreResult(1) should be(EmitLast(55))
    }
    /*"fold elements synchronously with big input" in {
      val p = instance(Produce(1 to 10000).fold(0)(_ + _))
      p.requestMoreResult(1) should be(EmitLast(10))
    }*/
    "create element spans" in {
      val p = instance(oneToTen.span(_ % 2 == 0).flatMap(identity))
      val EmitProducer(f) = p.requestMoreResult(1)

    }
  }

  def instance[I, O](op: Operation[I, O]): OpInstance[I, O] = ProcessorActor.instantiate(op)
}
