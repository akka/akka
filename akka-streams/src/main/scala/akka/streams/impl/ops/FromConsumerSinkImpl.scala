package akka.streams.impl.ops

import akka.streams.impl._
import akka.streams.Operation
import Operation.Sink
import akka.streams.impl._

object FromConsumerSinkImpl {
  def apply[I](upstream: Upstream, ctx: ContextEffects, sink: Sink[I]): SyncSink[I] =
    new SyncSink[I] with SyncSource {
      var downstream: Downstream[I] = _
      override def start(): Effect = ctx.subscribeFrom(sink)(handleSubscribe)
      def handleSubscribe(downstream: Downstream[I]): (SyncSource, Effect) = {
        this.downstream = downstream
        (this, Continue)
      }

      def handleNext(element: I): Effect = downstream.next(element)
      def handleComplete(): Effect = downstream.complete
      def handleError(cause: Throwable): Effect = downstream.error(cause)

      def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
      def handleCancel(): Effect = upstream.cancel
    }
}
