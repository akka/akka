package akka.streams.ops2

object IdentityImpl {
  def apply[O](upstream: Upstream, downstream: Downstream[O]): SyncOperation[O] =
    new SyncOperation[O] {
      def handleRequestMore(n: Int): Result = upstream.requestMore(n)
      def handleCancel(): Result = upstream.cancel

      def handleNext(element: O): Result = downstream.next(element)
      def handleComplete(): Result = downstream.complete
      def handleError(cause: Throwable): Result = downstream.error(cause)
    }
}
