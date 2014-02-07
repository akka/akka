package akka.streams.ops2

object IdentityImpl {
  def apply[O](upstream: Upstream, downstream: Downstream[O]): SyncOperation[O, O] =
    new SyncOperation[O, O] {
      def handleRequestMore(n: Int): Result[O] = upstream.requestMore(n)
      def handleCancel(): Result[O] = upstream.cancel

      def handleNext(element: O): Result[O] = downstream.next(element)
      def handleComplete(): Result[O] = downstream.complete
      def handleError(cause: Throwable): Result[O] = downstream.error(cause)
    }
}
