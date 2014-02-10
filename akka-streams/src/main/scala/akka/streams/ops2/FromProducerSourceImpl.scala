package akka.streams.ops2

import akka.streams.Operation.{ Source, FromProducerSource }

object FromProducerSourceImpl {
  def apply[O](downstream: Downstream[O], subscribable: Subscribable, source: Source[O]): SyncSource =
    new DynamicSyncSource {
      def initial = WaitingForRequest

      def WaitingForRequest: State =
        new State {
          def handleRequestMore(n: Int): Result = {
            val subscribed = new Subscribed(n)
            become(subscribed)
            subscribable.subscribeTo(source)(subscribed.onSubscribed)
          }
          def handleCancel(): Result = ???
        }

      class Subscribed(originallyRequested: Int) extends State {
        var subUpstream: Upstream = _
        def onSubscribed(upstream: Upstream): (SyncSink[O], Result) = {
          subUpstream = upstream
          (subDownstream, subUpstream.requestMore(originallyRequested))
        }

        def handleRequestMore(n: Int): Result = subUpstream.requestMore(n)
        def handleCancel(): Result = subUpstream.cancel

        val subDownstream = new SyncSink[O] {
          def handleNext(element: O): Result = downstream.next(element)
          def handleComplete(): Result = downstream.complete
          def handleError(cause: Throwable): Result = downstream.error(cause)
        }
      }
    }
}
