package akka.streams.ops2

import akka.streams.Operation.Sink

object FromConsumerSinkImpl {
  def apply[I](upstream: Upstream, subscribable: Subscribable, sink: Sink[I]): SyncSink[I, I] =
    new SyncSink[I, I] with SyncSource[I] {
      var downstream: Downstream[I] = _
      override def start(): Result[_] = subscribable.subscribeFrom(sink)(handleSubscribe)
      def handleSubscribe(downstream: Downstream[I]): (SyncSource[I], Result[I]) = {
        this.downstream = downstream
        (this, Continue)
      }

      def handleNext(element: I): Result[I] = downstream.next(element)
      def handleComplete(): Result[I] = downstream.complete
      def handleError(cause: Throwable): Result[I] = downstream.error(cause)

      def handleRequestMore(n: Int): Result[I] = upstream.requestMore(n)
      def handleCancel(): Result[I] = upstream.cancel
    }
}
