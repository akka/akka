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
            val subscribed = new Subscribing(n)
            become(subscribed)
            ctx.subscribeTo(source)(subscribed.onSubscribed)
          }
          def handleCancel(): Effect = ???
        }

      class Subscribing(var originallyRequested: Int) extends State {
        var cancelled = false
        def onSubscribed(subUpstream: Upstream): (SyncSink[O], Effect) =
          if (cancelled) (subDownstream, subUpstream.cancel)
          else {
            become(Subscribed(subUpstream))
            (subDownstream, subUpstream.requestMore(originallyRequested))
          }

        def handleRequestMore(n: Int): Effect = { originallyRequested += n; Continue }
        def handleCancel(): Effect = { cancelled = true; Continue }

        val subDownstream = new SyncSink[O] {
          def handleNext(element: O): Effect = downstream.next(element)
          def handleComplete(): Effect = downstream.complete
          def handleError(cause: Throwable): Effect = downstream.error(cause)
        }
      }
      def Subscribed(subUpstream: Upstream): State =
        new State {
          def handleRequestMore(n: Int): Effect = subUpstream.requestMore(n)
          def handleCancel(): Effect = subUpstream.cancel
        }
    }
}
