package akka.streams.impl.ops

import akka.streams.impl.{ Effect, SyncOperation, Downstream, Upstream }

object IdentityImpl {
  def apply[O](upstream: Upstream, downstream: Downstream[O]): SyncOperation[O] =
    new SyncOperation[O] {
      def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
      def handleCancel(): Effect = upstream.cancel

      def handleNext(element: O): Effect = downstream.next(element)
      def handleComplete(): Effect = downstream.complete
      def handleError(cause: Throwable): Effect = downstream.error(cause)
    }
}
