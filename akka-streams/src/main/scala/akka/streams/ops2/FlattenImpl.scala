package akka.streams.ops2

import akka.streams.Operation.Source

object FlattenImpl {
  def apply[O](upstream: Upstream, downstream: Downstream[O], subscribable: Subscribable): SyncOperation[Source[O], O] =
    new DynamicSyncOperation[Source[O], O] {
      def initial: State = Waiting

      def Waiting: State =
        new State {
          def handleRequestMore(n: Int): Result[O] = {
            become(WaitingForElement(n))
            upstream.requestMore(1)
          }
          def handleCancel(): Result[O] = ???

          def handleNext(element: Source[O]): Result[O] = throw new IllegalStateException("No element requested")
          def handleComplete(): Result[O] = downstream.complete
          def handleError(cause: Throwable): Result[O] = downstream.error(cause)
        }

      def WaitingForElement(remaining: Int): State =
        new State {
          def handleRequestMore(n: Int): Result[O] = {
            become(WaitingForElement(remaining + n))
            Continue
          }
          def handleCancel(): Result[O] = ???

          def handleNext(element: Source[O]): Result[O] = {
            val readSubstream = new ReadSubstream(remaining)
            become(readSubstream)
            subscribable.subscribeTo(element)(readSubstream.setSubUpstream)
          }
          def handleComplete(): Result[O] = downstream.complete
          def handleError(cause: Throwable): Result[O] = downstream.error(cause)
        }

      class ReadSubstream(var remaining: Int) extends State {
        var subUpstream: Upstream = _
        var closeAtEnd = false
        def setSubUpstream(upstream: Upstream): (SyncSink[O, O], Result[O]) = {
          subUpstream = upstream
          (subDownstream, subUpstream.requestMore(remaining))
        }

        def handleRequestMore(n: Int): Result[O] = {
          remaining += n
          subUpstream.requestMore(n)
        }
        def handleCancel(): Result[O] = ???

        def handleNext(element: Source[O]): Result[O] = ???
        def handleComplete(): Result[O] = {
          closeAtEnd = true
          Continue
        }
        def handleError(cause: Throwable): Result[O] = ???

        val subDownstream = new SyncSink[O, O] {
          def handleNext(element: O): Result[O] = {
            remaining -= 1
            downstream.next(element)
          }

          def handleComplete(): Result[O] = {
            if (closeAtEnd) downstream.complete
            else if (remaining > 0) {
              become(WaitingForElement(remaining))
              upstream.requestMore(1)
            } else {
              become(Waiting)
              Continue
            }
          }

          def handleError(cause: Throwable): Result[O] = ???
        }
      }
    }
}
