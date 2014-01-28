package akka.streams

import org.scalatest.{ ShouldMatchers, WordSpec }
import ProcessorActor._
import rx.async.api.Producer

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
      case class SubEmit(i: Int) extends SideEffect[Producer[Int]] {
        def run(): Result[Producer[Int]] = ???
      }
      case object SubComplete extends SideEffect[Nothing] {
        def run(): Result[Nothing] = ???
      }
      case class SubError(cause: Throwable) extends SideEffect[Nothing] {
        def run(): Result[Nothing] = ???
      }
      object MyPublisherResults extends PublisherResults[Int] {
        def emit(o: Int): Result[Producer[Int]] = SubEmit(o)
        def complete: Result[Producer[Int]] = SubComplete
        def error(cause: Throwable): Result[Producer[Int]] = SubError(cause)
      }

      val p = instance[Nothing, Producer[Int]](Produce(1 to 6).span(_ % 3 == 0))
      val EmitProducerFinished(f) = p.handle(RequestMore(1))
      val handler = f(MyPublisherResults)
      p.handle(RequestMore(1)) should be(Continue)

      handler.handle(RequestMore(1)) should be(SubEmit(1))
      handler.handle(RequestMore(1)) should be(SubEmit(2))
      val Combine(Combine(SubEmit(3), SubComplete), EmitProducerFinished(next)) = handler.handle(RequestMore(1))

      val nextHandler = next(MyPublisherResults)
      nextHandler.handle(RequestMore(1)) should be(SubEmit(4))
      nextHandler.handle(RequestMore(1)) should be(SubEmit(5))
      nextHandler.handle(RequestMore(1)) should be(SubEmit(6) ~ SubComplete ~ Complete)
    }
  }

  def instance[I, O](op: Operation[I, O]): OpInstance[I, O] = ProcessorActor.instantiate(op)
}
