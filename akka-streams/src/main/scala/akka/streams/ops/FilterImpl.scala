package akka.streams
package ops

object FilterImpl {
  def apply[I](filter: Filter[I]): OpInstance[I, I] =
    new OpInstance[I, I] {
      def handle(result: SimpleResult[I]): Result[I] = result match {
        case r: RequestMore ⇒ r /* + heuristic of previously filtered? */
        case Emit(i)        ⇒ if (filter.pred(i)) Emit(i) else Continue ~ RequestMore(1) // FIXME: error handling
        case EmitMany(is) ⇒
          val (matching, skipped) = is.partition(filter.pred)

          val result =
            if (matching.size == 1) Emit(matching.head)
            else if (matching.isEmpty) Continue
            else EmitMany(matching)

          val requestRemaining =
            if (skipped.size > 0) RequestMore(skipped.size) else Continue

          result ~ requestRemaining
        case Complete ⇒ Complete
        case e: Error ⇒ e
      }
    }
}
