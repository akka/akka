package akka.streams.ops2

object MapImpl {
  def apply[I, O](upstream: Upstream, downstream: Downstream[O], f: I ⇒ O): SyncOperation[I, O] =
    new SyncOperation[I, O] {
      def handleRequestMore(n: Int): Result[O] = upstream.requestMore(n)
      def handleCancel(): Result[O] = upstream.cancel

      def handleNext(element: I): Result[O] = downstream.next(f(element))
      def handleComplete(): Result[O] = downstream.complete
      def handleError(cause: Throwable): Result[O] = downstream.error(cause)
    }
}
