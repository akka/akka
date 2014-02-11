package akka.streams
package impl
package ops

import akka.streams.impl._
import rx.async.api.Processor
import Operation._

class FromProcessorOperationImpl[I, O](upstream: Upstream,
                                       downstream: Downstream[O],
                                       ctx: ContextEffects,
                                       processor: Processor[I, O]) extends DynamicSyncOperation[I] {
  def initial: State = WaitingForRequest

  def WaitingForRequest = new State {
    def handleRequestMore(n: Int): Effect = {
      val newState = new WaitingForSubscriptions(n)
      become(newState)
      ctx.subscribeTo(processor)(newState.onUpstreamSubscribe) ~
        ctx.subscribeFrom(processor)(newState.onDownstreamSubscribe)
    }
    def handleCancel(): Effect = upstream.cancel

    def handleNext(element: I): Effect = throw new IllegalStateException()
    def handleComplete(): Effect = throw new IllegalStateException()
    def handleError(cause: Throwable): Effect = throw new IllegalStateException()
  }

  class WaitingForSubscriptions(var originallyRequested: Int) extends State {
    var innerUpstream: Upstream = _
    var innerDownstream: Downstream[I] = _

    val innerSink = new SyncSink[O] {
      def handleNext(element: O): Effect = downstream.next(element)
      def handleComplete(): Effect = downstream.complete
      def handleError(cause: Throwable): Effect = downstream.error(cause)
    }
    val innerSource = new SyncSource {
      def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
      def handleCancel(): Effect = upstream.cancel
    }

    def onUpstreamSubscribe(upstream: Upstream): (SyncSink[O], Effect) = {
      innerUpstream = upstream
      (innerSink, checkComplete())
    }
    def onDownstreamSubscribe(downstream: Downstream[I]): (SyncSource, Effect) = {
      innerDownstream = downstream
      (innerSource, checkComplete())
    }

    def checkComplete(): Effect =
      if ((innerUpstream ne null) && (innerDownstream ne null)) {
        become(Running(innerUpstream, innerDownstream))
        innerUpstream.requestMore(originallyRequested)
      } else Continue

    def handleRequestMore(n: Int): Effect = { originallyRequested += n; Continue }
    def handleCancel(): Effect = upstream.cancel

    def handleNext(element: I): Effect = throw new IllegalStateException()
    def handleComplete(): Effect = throw new IllegalStateException()
    def handleError(cause: Throwable): Effect = throw new IllegalStateException()
  }
  def Running(innerUpstream: Upstream, innerDownstream: Downstream[I]): State = new State {
    def handleRequestMore(n: Int): Effect = innerUpstream.requestMore(n)
    def handleCancel(): Effect = innerUpstream.cancel

    def handleNext(element: I): Effect = innerDownstream.next(element)
    def handleComplete(): Effect = innerDownstream.complete
    def handleError(cause: Throwable): Effect = innerDownstream.error(cause)
  }
}
