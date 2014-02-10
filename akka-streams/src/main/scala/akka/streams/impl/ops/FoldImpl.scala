package akka.streams.impl.ops

import akka.streams.impl._
import akka.streams.Operation
import Operation.Fold
import akka.streams.impl.{ Effect, SyncOperation, Downstream, Upstream }

object FoldImpl {
  def apply[I, O](upstream: Upstream, downstream: Downstream[O], fold: Fold[I, O], batchSize: Int = 100): SyncOperation[I] =
    new SyncOperation[I] {
      var remaining = 0
      var z = fold.seed
      def handleRequestMore(n: Int): Effect =
        upstream.requestMore(batchSize)

      def handleCancel(): Effect = upstream.cancel

      def handleNext(element: I): Effect = {
        z = fold.f(z, element)
        upstream.requestMore(1)
      }

      def handleComplete(): Effect = downstream.next(z) ~ downstream.complete
      def handleError(cause: Throwable): Effect = downstream.error(cause)
    }
}
