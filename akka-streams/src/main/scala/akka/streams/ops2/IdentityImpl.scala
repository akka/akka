package akka.streams.ops2

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
