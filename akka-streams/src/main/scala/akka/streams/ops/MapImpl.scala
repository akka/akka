package akka.streams
package ops

import akka.streams.Operation.Map

object MapImpl {
  def apply[I, O](map: Map[I, O]): OpInstance[I, O] =
    new OpInstance[I, O] {
      val f = map.f
      def handle(result: SimpleResult[I]): Result[O] = result match {
        case r: RequestMore ⇒ r
        case Emit(i)        ⇒ Emit(f(i)) // FIXME: error handling
        case EmitMany(is)   ⇒ EmitMany(is.map(f)) // FIXME: error handling
        case Complete       ⇒ Complete
        case e: Error       ⇒ e
      }
    }
}
