package akka.streams
package impl
package ops

import Operation.Source

object FromProducerSourceImpl {
  def apply[O](downstream: Downstream[O], ctx: ContextEffects, source: Source[O]): SyncSource =
    new DynamicSyncSource {
      def initial = WaitingForRequest

      def WaitingForRequest: State =
        new State {
          def handleRequestMore(n: Int): Effect = {
            val subscribed = new Subscribed(n)
            become(subscribed)
            ctx.subscribeTo(source)(subscribed.onSubscribed)
          }
          def handleCancel(): Effect = ???
        }

      class Subscribed(originallyRequested: Int) extends State {
        var subUpstream: Upstream = _
        def onSubscribed(upstream: Upstream): (SyncSink[O], Effect) = {
          subUpstream = upstream
          (subDownstream, subUpstream.requestMore(originallyRequested))
        }

        def handleRequestMore(n: Int): Effect = subUpstream.requestMore(n)
        def handleCancel(): Effect = subUpstream.cancel

        val subDownstream = new SyncSink[O] {
          def handleNext(element: O): Effect = downstream.next(element)
          def handleComplete(): Effect = downstream.complete
          def handleError(cause: Throwable): Effect = downstream.error(cause)
        }
      }
    }
}
