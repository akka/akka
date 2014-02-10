package akka.streams.ops2

import akka.streams.Operation.Source

object FlattenImpl {
  def apply[O](upstream: Upstream, downstream: Downstream[O], subscribable: Subscribable): SyncOperation[Source[O]] =
    new DynamicSyncOperation[Source[O]] {
      def initial: State = Waiting

      def Waiting: State =
        new State {
          def handleRequestMore(n: Int): Result = {
            become(WaitingForElement(n))
            upstream.requestMore(1)
          }
          def handleCancel(): Result = ???

          def handleNext(element: Source[O]): Result = throw new IllegalStateException("No element requested")
          def handleComplete(): Result = downstream.complete
          def handleError(cause: Throwable): Result = downstream.error(cause)
        }

      def WaitingForElement(remaining: Int): State =
        new State {
          def handleRequestMore(n: Int): Result = {
            become(WaitingForElement(remaining + n))
            Continue
          }
          def handleCancel(): Result = ???

          def handleNext(element: Source[O]): Result = {
            val readSubstream = new ReadSubstream(remaining)
            become(readSubstream)
            subscribable.subscribeTo(element)(readSubstream.setSubUpstream)
          }
          def handleComplete(): Result = downstream.complete
          def handleError(cause: Throwable): Result = downstream.error(cause)
        }

      class ReadSubstream(var remaining: Int) extends State {
        var subUpstream: Upstream = _
        var closeAtEnd = false
        def setSubUpstream(upstream: Upstream): (SyncSink[O], Result) = {
          subUpstream = upstream
          (subDownstream, subUpstream.requestMore(remaining))
        }

        def handleRequestMore(n: Int): Result = {
          remaining += n
          subUpstream.requestMore(n)
        }
        def handleCancel(): Result = ???

        def handleNext(element: Source[O]): Result = ???
        def handleComplete(): Result = {
          closeAtEnd = true
          Continue
        }
        def handleError(cause: Throwable): Result = ???

        val subDownstream = new SyncSink[O] {
          def handleNext(element: O): Result = {
            remaining -= 1
            downstream.next(element)
          }

          def handleComplete(): Result = {
            if (closeAtEnd) downstream.complete
            else if (remaining > 0) {
              become(WaitingForElement(remaining))
              upstream.requestMore(1)
            } else {
              become(Waiting)
              Continue
            }
          }

          def handleError(cause: Throwable): Result = ???
        }
      }
    }
}
