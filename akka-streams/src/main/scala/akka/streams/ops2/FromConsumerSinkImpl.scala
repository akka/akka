package akka.streams.ops2

import akka.streams.Operation.Sink

object FromConsumerSinkImpl {
  def apply[I](upstream: Upstream, subscribable: Subscribable, sink: Sink[I]): SyncSink[I] =
    new SyncSink[I] with SyncSource {
      var downstream: Downstream[I] = _
      override def start(): Result = subscribable.subscribeFrom(sink)(handleSubscribe)
      def handleSubscribe(downstream: Downstream[I]): (SyncSource, Result) = {
        this.downstream = downstream
        (this, Continue)
      }

      def handleNext(element: I): Result = downstream.next(element)
      def handleComplete(): Result = downstream.complete
      def handleError(cause: Throwable): Result = downstream.error(cause)

      def handleRequestMore(n: Int): Result = upstream.requestMore(n)
      def handleCancel(): Result = upstream.cancel
    }
}
