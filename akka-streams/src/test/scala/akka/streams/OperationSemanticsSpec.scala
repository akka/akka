package akka.streams

import org.scalatest.{ ShouldMatchers, WordSpec }
import ProcessorActor._

class OperationSemanticsSpec extends WordSpec with ShouldMatchers {
  val oneToTen = Produce(1 to 10)
  import Operations._

  "Operations" should {
    "fold elements synchronously with small input" in {
      val p = instance[Nothing, Int](oneToTen.fold(0)(_ + _))
      p.handle(RequestMore(1)) should be(Emit(55) ~ Complete)
    }
    "fold elements synchronously with big input" in {
      val p = instance[Nothing, Long](Produce(1L to 1000000L).fold(0L)(_ + _))
      p.handle(RequestMore(1)) should be(Emit(500000500000L) ~ Complete)
    }
    "create element spans" in {
      val p = instance(oneToTen.span(_ % 2 == 0).flatMap(identity))
      val EmitProducer(f) = p.requestMoreResult(1)

    }
  }

  def instance[I, O](op: Operation[I, O]): OpInstance[I, O] = ProcessorActor.instantiate(op)
}
