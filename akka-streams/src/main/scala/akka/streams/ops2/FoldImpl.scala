package akka.streams.ops2

import akka.streams.Operation.DirectFold

object FoldImpl {
  def apply[I, O](upstream: Upstream, downstream: Downstream[O], directFold: DirectFold[I, O]): SyncOperation[I, O] =
    new SyncOperation[I, O] {
      val batchSize = 100
      var remaining = 0
      var z = directFold.seed
      def handleRequestMore(n: Int): Result[O] =
        upstream.requestMore(batchSize)

      def handleCancel(): Result[O] = upstream.cancel

      def handleNext(element: I): Result[O] = {
        z = directFold.f(z, element)
        upstream.requestMore(1)
      }

      def handleComplete(): Result[O] = downstream.next(z) ~ downstream.complete
      def handleError(cause: Throwable): Result[O] = downstream.error(cause)
    }
}
