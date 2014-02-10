package akka.streams.impl.ops

import akka.streams.impl._
import akka.streams.Operation
import Operation.Source
import akka.streams.impl._

object FlattenImpl {
  def apply[O](upstream: Upstream, downstream: Downstream[O], ctx: ContextEffects): SyncOperation[Source[O]] =
    new DynamicSyncOperation[Source[O]] {
      def initial: State = Waiting

      def Waiting: State =
        new State {
          def handleRequestMore(n: Int): Effect = {
            become(WaitingForElement(n))
            upstream.requestMore(1)
          }
          def handleCancel(): Effect = ???

          def handleNext(element: Source[O]): Effect = throw new IllegalStateException("No element requested")
          def handleComplete(): Effect = downstream.complete
          def handleError(cause: Throwable): Effect = downstream.error(cause)
        }

      def WaitingForElement(remaining: Int): State =
        new State {
          def handleRequestMore(n: Int): Effect = {
            become(WaitingForElement(remaining + n))
            Continue
          }
          def handleCancel(): Effect = ???

          def handleNext(element: Source[O]): Effect = {
            val readSubstream = new ReadSubstream(remaining)
            become(readSubstream)
            ctx.subscribeTo(element)(readSubstream.setSubUpstream)
          }
          def handleComplete(): Effect = downstream.complete
          def handleError(cause: Throwable): Effect = downstream.error(cause)
        }

      class ReadSubstream(var remaining: Int) extends State {
        var subUpstream: Upstream = _
        var closeAtEnd = false
        def setSubUpstream(upstream: Upstream): (SyncSink[O], Effect) = {
          subUpstream = upstream
          (subDownstream, subUpstream.requestMore(remaining))
        }

        def handleRequestMore(n: Int): Effect = {
          remaining += n
          subUpstream.requestMore(n)
        }
        def handleCancel(): Effect = ???

        def handleNext(element: Source[O]): Effect = ???
        def handleComplete(): Effect = {
          closeAtEnd = true
          Continue
        }
        def handleError(cause: Throwable): Effect = ???

        val subDownstream = new SyncSink[O] {
          def handleNext(element: O): Effect = {
            remaining -= 1
            downstream.next(element)
          }

          def handleComplete(): Effect = {
            if (closeAtEnd) downstream.complete
            else if (remaining > 0) {
              become(WaitingForElement(remaining))
              upstream.requestMore(1)
            } else {
              become(Waiting)
              Continue
            }
          }

          def handleError(cause: Throwable): Effect = ???
        }
      }
    }
}
