package akka.streams.impl.ops

import akka.streams.impl._
import akka.streams.Operation.FromProducerSource

class FromProducerSourceImpl[O](downstream: Downstream[O], ctx: ContextEffects, source: FromProducerSource[O]) extends DynamicSyncSource {
  def initial = WaitingForRequest

  def WaitingForRequest: State =
    new State {
      def handleRequestMore(n: Int): Effect = startSubscribing(n)
      def handleCancel(): Effect = ???
    }

  def startSubscribing(requested: Int): Effect = {
    val nextState = new WaitingForSubscription(requested)
    become(nextState)
    ctx.subscribeTo(source)(nextState.onSubscribed)
  }

  class WaitingForSubscription(var originallyRequested: Int) extends State {
    def onSubscribed(upstream: Upstream): (SyncSink[O], Effect) = {
      become(Subscribed(upstream))
      (subDownstream, if (originallyRequested > 0) upstream.requestMore(originallyRequested) else Continue)
    }

    def handleRequestMore(n: Int): Effect = {
      originallyRequested += n
      Continue
    }
    def handleCancel(): Effect = ???
  }
  def Subscribed(subUpstream: Upstream) = new State {
    def handleRequestMore(n: Int): Effect = subUpstream.requestMore(n)
    def handleCancel(): Effect = subUpstream.cancel
  }

  val subDownstream = new SyncSink[O] {
    def handleNext(element: O): Effect = downstream.next(element)
    def handleComplete(): Effect = downstream.complete
    def handleError(cause: Throwable): Effect = downstream.error(cause)
  }
}
