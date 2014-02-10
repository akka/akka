package akka.streams.ops2

import scala.collection.immutable.VectorBuilder
import scala.annotation.tailrec
import rx.async.api.Producer
import akka.streams.Operation.{ Source, FromProducerSource }

object FromProducerSourceImpl {
  def apply[O](downstream: Downstream[O], subscribable: Subscribable, source: Source[O]): SyncSource[O] =
    new DynamicSyncSource[O] {
      def initial = WaitingForRequest

      def WaitingForRequest: State =
        new State {
          def handleRequestMore(n: Int): Result[O] = {
            val subscribed = new Subscribed(n)
            become(subscribed)
            subscribable.subscribeTo(source)(subscribed.onSubscribed)
          }
          def handleCancel(): Result[O] = ???
        }

      class Subscribed(originallyRequested: Int) extends State {
        var subUpstream: Upstream = _
        def onSubscribed(upstream: Upstream): (SyncSink[O, O], Result[O]) = {
          subUpstream = upstream
          (subDownstream, subUpstream.requestMore(originallyRequested))
        }

        def handleRequestMore(n: Int): Result[O] = subUpstream.requestMore(n)
        def handleCancel(): Result[O] = subUpstream.cancel

        val subDownstream = new SyncSink[O, O] {
          def handleNext(element: O): Result[O] = downstream.next(element)
          def handleComplete(): Result[O] = downstream.complete
          def handleError(cause: Throwable): Result[O] = downstream.error(cause)
        }
      }
    }
}
