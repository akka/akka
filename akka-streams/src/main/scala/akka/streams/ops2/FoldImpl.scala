package akka.streams.ops2

import akka.streams.Operation.DirectFold

object FoldImpl {
  def apply[I, O](upstream: Upstream, downstream: Downstream[O], directFold: DirectFold[I, O]): SyncOperation[I] =
    new SyncOperation[I] {
      val batchSize = 100
      var remaining = 0
      var z = directFold.seed
      def handleRequestMore(n: Int): Result =
        upstream.requestMore(batchSize)

      def handleCancel(): Result = upstream.cancel

      def handleNext(element: I): Result = {
        z = directFold.f(z, element)
        upstream.requestMore(1)
      }

      def handleComplete(): Result = downstream.next(z) ~ downstream.complete
      def handleError(cause: Throwable): Result = downstream.error(cause)
    }
}
