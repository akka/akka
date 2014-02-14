package akka.streams.impl.ops

import akka.streams.Operation.{ Source, Span }
import akka.streams.impl._
import akka.streams.Operation.Span

class SpanImpl[I](upstream: Upstream, downstream: Downstream[Source[I]], span: Span[I]) extends DynamicSyncOperation[I] {
  def initial: State = WaitingForRequest

  var subStreamsRequested = 0

  def WaitingForRequest: State = new State {
    def handleRequestMore(n: Int): Effect = {
      subStreamsRequested += n
      become(WaitingForFirstElement)
      upstream.requestMore(1)
    }
    def handleCancel(): Effect = upstream.cancel

    def handleNext(element: I): Effect = ???
    def handleComplete(): Effect = downstream.complete
    def handleError(cause: Throwable): Effect = downstream.error(cause)
  }
  val WaitingForFirstElement: State = new State {
    def handleRequestMore(n: Int): Effect = {
      subStreamsRequested += n
      Continue
    }
    def handleCancel(): Effect = ???

    def handleNext(element: I): Effect = {
      // FIXME: case when first element matches predicate, in which case we could emit a singleton Producer
      subStreamsRequested -= 1
      val running = new Running(element)
      become(running)
      downstream.next(InternalSource(running.onSubscribed))
    }
    def handleComplete(): Effect = downstream.complete
    def handleError(cause: Throwable): Effect = ???
  }
  class Running(firstElement: I) extends State {
    var subDownstream: Downstream[I] = _
    var undeliveredElements = -1

    def onSubscribed(downstream: Downstream[I]): (SyncSource, Effect) = {
      this.subDownstream = downstream
      (subSource, Continue)
    }
    val subSource = new SyncSource {
      def handleRequestMore(n: Int): Effect = {
        val deliverFirst = if (undeliveredElements == -1) subDownstream.next(firstElement) else Continue

        undeliveredElements += n

        deliverFirst ~ {
          if (undeliveredElements > 0) upstream.requestMore(1)
          else Continue
        }
      }
      def handleCancel(): Effect = ???
    }

    def handleRequestMore(n: Int): Effect = {
      subStreamsRequested += n
      Continue
    }
    def handleCancel(): Effect = ???

    def handleNext(element: I): Effect = {
      undeliveredElements -= 1
      if (span.p(element)) {
        val data = subDownstream.next(element) ~ subDownstream.complete
        if (subStreamsRequested > 0) {
          become(WaitingForFirstElement)
          data ~ upstream.requestMore(1)
        } else {
          become(WaitingForRequest)
          data
        }
      } else
        subDownstream.next(element) ~ (if (undeliveredElements > 0) upstream.requestMore(1) else Continue)
    }
    def handleComplete(): Effect = subDownstream.complete ~ downstream.complete
    def handleError(cause: Throwable): Effect = ???
  }
}
