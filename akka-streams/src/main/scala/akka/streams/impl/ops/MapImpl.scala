package akka.streams.impl.ops

import akka.streams.impl.{ Effect, SyncOperation, Downstream, Upstream }

object MapImpl {
  def apply[I, O](upstream: Upstream, downstream: Downstream[O], f: I ⇒ O): SyncOperation[I] =
    new SyncOperation[I] {
      def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
      def handleCancel(): Effect = upstream.cancel

      def handleNext(element: I): Effect = downstream.next(f(element))
      def handleComplete(): Effect = downstream.complete
      def handleError(cause: Throwable): Effect = downstream.error(cause)
    }
}
