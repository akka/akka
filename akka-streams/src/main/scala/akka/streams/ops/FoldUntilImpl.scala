package akka.streams
package ops

object FoldUntilImpl {
  def apply[I, O, Z](fold: FoldUntil[I, O, Z]): OpInstance[I, O] =
    new OpInstance[I, O] {
      var z = fold.seed

      def handle(result: SimpleResult[I]): Result[O] = result match {
        case RequestMore(n) ⇒ ??? // TODO: how much to request?
        case Emit(i) ⇒
          fold.acc(z, i) match {
            case FoldResult.Emit(value, nextSeed) ⇒
              z = nextSeed
              Emit(value)
            case FoldResult.Continue(newZ) ⇒
              z = newZ
              Continue
          }
        case Complete ⇒
          // TODO: could also define that the latest seed should be returned, or flag an error
          //       if the last element doesn't match the predicate
          Complete
        case e: Error ⇒ e
      }
    }
}
