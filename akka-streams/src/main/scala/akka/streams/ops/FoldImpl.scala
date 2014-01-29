package akka.streams
package ops

object FoldImpl {
  def apply[I, O](fold: Fold[I, O]): OpInstance[I, O] =
    new OpInstance[I, O] {
      val batchSize = 100
      var missing = 0
      var z = fold.z
      var completed = false
      def requestMore(n: Int): Int = {
        // we can instantly consume all values, even if there's just one requested,
        // though we probably need to be careful with MaxValue which may lead to
        // overflows easily

        missing = batchSize
        batchSize
      }

      def handle(result: SimpleResult[I]): Result[O] = result match {
        case RequestMore(_) ⇒
          missing = batchSize
          RequestMore(batchSize)
        case Emit(i) ⇒
          z = fold.acc(z, i) // FIXME: error handling
          missing -= 1
          maybeRequestMore
        case EmitMany(is) ⇒
          z = is.foldLeft(z)(fold.acc) // FIXME: error handling
          missing -= is.size
          maybeRequestMore
        case Complete ⇒
          if (!completed) {
            completed = true
            Emit(z) ~ Complete
          } else Continue
        case e: Error ⇒ e
      }
      def maybeRequestMore: Result[O] =
        if (missing == 0) {
          missing = batchSize
          RequestMore(batchSize)
        } else Continue
    }
}
