package akka.streams.ops2

object MapImpl {
  def apply[I, O](upstream: Upstream, downstream: Downstream[O], f: I ⇒ O): SyncOperation[I] =
    new SyncOperation[I] {
      def handleRequestMore(n: Int): Result = upstream.requestMore(n)
      def handleCancel(): Result = upstream.cancel

      def handleNext(element: I): Result = downstream.next(f(element))
      def handleComplete(): Result = downstream.complete
      def handleError(cause: Throwable): Result = downstream.error(cause)
    }
}
