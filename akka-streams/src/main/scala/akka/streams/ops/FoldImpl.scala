package akka.streams
package ops

object FoldImpl {
  def apply[I, O](fold: Fold[I, O]): OpInstance[I, O] =
    new OpInstance[I, O] {
      val batchSize = 100
      var missing = 0
      var z = fold.z
      def requestMore(n: Int): Int = {
        // we request values in batches of batchSize. This could be made configurable later on.

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
        case Complete ⇒ Emit(z) ~ Complete
        case e: Error ⇒ e
      }
      def maybeRequestMore: Result[O] =
        if (missing == 0) {
          missing = batchSize
          RequestMore(batchSize)
        } else Continue
    }
}
