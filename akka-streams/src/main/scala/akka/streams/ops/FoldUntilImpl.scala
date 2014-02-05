package akka.streams
package ops

import akka.streams.Operation.FoldUntil
import scala.annotation.tailrec

object FoldUntilImpl {
  def apply[I, O, Z](fold: FoldUntil[I, O, Z]): OpInstance[I, O] =
    new OpInstance[I, O] {
      var z = fold.seed
      val batchSize = 100

      def handle(result: SimpleResult[I]): Result[O] = result match {
        case RequestMore(n) ⇒ RequestMore(batchSize)
        case Emit(i)        ⇒ handleResult(fold.onNext(z, i))
        case EmitMany(is)   ⇒ handleSeveral(is)
        case Complete ⇒
          // TODO: could also define that the latest seed should be returned, or flag an error
          //       if the last element doesn't match the predicate
          fold.onComplete(z).map(Emit(_)).getOrElse(Continue) ~ Complete
        case e: Error ⇒ e
      }

      @tailrec def handleSeveral(remaining: Vector[I], resultValues: Vector[O] = Vector.empty, requestMore: Int = 0): Result[O] =
        if (remaining.isEmpty) Result.emitMany(resultValues) ~ Result.requestMore(requestMore)
        else fold.onNext(z, remaining.head) match {
          case FoldUntil.Emit(value, nextSeed) ⇒
            z = nextSeed
            handleSeveral(remaining.tail, resultValues :+ value, requestMore + 1)
          case FoldUntil.Continue(newZ) ⇒
            z = newZ
            handleSeveral(remaining.tail, resultValues, requestMore + 1)
          case FoldUntil.EmitAndStop(value) ⇒
            Result.emitMany(resultValues :+ value) ~ Complete // ~ and send Stop
          case FoldUntil.Stop ⇒ Continue // Stop
        }

      def handleResult(result: FoldUntil.Command[O, Z]): Result[O] = result match {
        case FoldUntil.Emit(value, nextSeed) ⇒
          z = nextSeed
          Emit(value) ~ RequestMore(1)
        case FoldUntil.Continue(newZ) ⇒
          z = newZ
          RequestMore(1)
        case FoldUntil.EmitAndStop(value) ⇒ Emit(value) ~ Complete // ~ and send Stop
        case FoldUntil.Stop               ⇒ Continue // Stop
      }
    }
}
