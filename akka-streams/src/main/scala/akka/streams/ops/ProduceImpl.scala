package akka.streams
package ops

import scala.collection.immutable.VectorBuilder
import scala.annotation.tailrec

object ProduceImpl {
  def apply[O](produce: Produce[O]): OpInstance[Nothing, O] =
    new OpInstance[Nothing, O] {
      val it = produce.elements.iterator

      def handle(result: SimpleResult[Nothing]): Result[O] = result match {
        case RequestMore(n) ⇒ requestMore(n)
      }

      def requestMore(n: Int): Result[O] =
        if (n > 0)
          if (it.hasNext)
            if (n == 1) Emit(it.next()) ~ maybeComplete
            else EmitMany(rec(new VectorBuilder[O], n)) ~ maybeComplete
          else Complete
        else throw new IllegalStateException(s"n = $n is not > 0")

      def maybeComplete = if (it.hasNext) Continue else Complete

      @tailrec def rec(result: VectorBuilder[O], remaining: Int): Vector[O] =
        if (remaining > 0 && it.hasNext) rec(result += it.next(), remaining - 1)
        else result.result()
    }
}
