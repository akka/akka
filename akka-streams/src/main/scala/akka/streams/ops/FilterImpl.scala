package akka.streams
package ops

object FilterImpl {
  def apply[I](filter: Filter[I]): OpInstance[I, I] =
    new OpInstance[I, I] {
      def handle(result: SimpleResult[I]): Result[I] = result match {
        case r: RequestMore ⇒ r /* + heuristic of previously filtered? */
        case Emit(i)        ⇒ if (filter.pred(i)) Emit(i) else Continue ~ RequestMore(1) // FIXME: error handling
        case e: EmitMany[I] ⇒ handleMany(e)
        case Complete       ⇒ Complete
        case e: Error       ⇒ e
      }
    }
}
