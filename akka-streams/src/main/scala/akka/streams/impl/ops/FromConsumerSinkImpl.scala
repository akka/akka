package akka.streams.impl.ops

import akka.streams.Operation
import akka.streams.impl._
import Operation.Sink

class FromConsumerSinkImpl[I](upstream: Upstream, ctx: ContextEffects, sink: Sink[I]) extends SyncSink[I] {
  var downstream: Downstream[I] = _
  override def start(): Effect = ctx.subscribeFrom(sink)(handleSubscribe)
  def handleSubscribe(downstream: Downstream[I]): SyncSource = {
    this.downstream = downstream
    internalSource
  }

  val internalSource = new SyncSource {
    def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
    def handleCancel(): Effect = upstream.cancel
  }

  def handleNext(element: I): Effect = downstream.next(element)
  def handleComplete(): Effect = downstream.complete
  def handleError(cause: Throwable): Effect = downstream.error(cause)
}
