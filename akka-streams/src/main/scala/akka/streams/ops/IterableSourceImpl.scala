package akka.streams
package ops

import scala.collection.immutable.VectorBuilder
import scala.annotation.tailrec

object IterableSourceImpl {
  def apply[O](iterable: Iterable[O]): OpInstance[Nothing, O] =
    new OpInstance[Nothing, O] {
      override def toString: String = "IterableSource"

      val it = iterable.iterator

      def handle(result: SimpleResult[Nothing]): Result[O] = result match {
        case RequestMore(n) ⇒ requestMore(n)
      }

      def requestMore(n: Int): Result[O] =
        if (n > 0)
          if (it.hasNext)
            if (n == 1) Emit(it.next()) ~ maybeCompleted()
            else EmitMany(rec(new VectorBuilder[O], n)) ~ maybeCompleted()
          else maybeCompleted()
        else throw new IllegalStateException(s"n = $n is not > 0")

      var alreadyCompleted = false
      def maybeCompleted() = if (it.hasNext || alreadyCompleted) Continue else {
        alreadyCompleted = true
        Complete
      }

      @tailrec def rec(result: VectorBuilder[O], remaining: Int): Vector[O] =
        if (remaining > 0 && it.hasNext) rec(result += it.next(), remaining - 1)
        else result.result()
    }
}
