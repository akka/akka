package akka.streams.impl.ops

import akka.streams.Operation.{ SingletonSource, Source, Span }
import akka.streams.impl._

class SpanImpl[I](upstream: Upstream, downstream: Downstream[Source[I]], span: Span[I]) extends DynamicSyncOperation[I] {
  def initial: State = WaitingForRequest
  val subSource = new DynamicSyncSource {
    def initial: State = SubCompleted
  }

  var subStreamsRequested = 0

  def WaitingForRequest: State = new RejectNext {
    def handleRequestMore(n: Int): Effect = {
      subStreamsRequested += n
      become(WaitingForFirstElement)
      upstream.requestMore(1)
    }
    def handleCancel(): Effect = upstream.cancel

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
      subStreamsRequested -= 1

      if (span.p(element)) {
        downstream.next(SingletonSource(element)) ~ {
          if (subStreamsRequested > 0) upstream.requestMore(1)
          else Continue
        }
      } else {
        val running = new Subscribing(element)
        become(running)
        downstream.next(InternalSource(running.onSubscribed))
      }
    }
    def handleComplete(): Effect = downstream.complete
    def handleError(cause: Throwable): Effect = ???
  }
  class Subscribing(firstElement: I) extends RejectNext {
    var completed = false
    def onSubscribed(downstream: Downstream[I]): (SyncSource, Effect) = {
      become(
        if (completed) CompleteAfter(downstream, firstElement)
        else FirstElementUndelivered(downstream, firstElement))
      (subSource, Continue)
    }

    def handleRequestMore(n: Int): Effect = { subStreamsRequested += n; Continue }
    def handleCancel(): Effect = ???

    def handleComplete(): Effect = { completed = true; downstream.complete }
    def handleError(cause: Throwable): Effect = ???
  }
  def FirstElementUndelivered(subDownstream: Downstream[I], firstElement: I): State = new RejectNext {
    subSource.become(new SyncSource {
      def handleRequestMore(n: Int): Effect = {
        become(Running(subDownstream, n - 1))
        subDownstream.next(firstElement) ~ (if (n > 1) upstream.requestMore(1) else Continue)
      }
      def handleCancel(): Effect = ???
    })

    def handleRequestMore(n: Int): Effect = { subStreamsRequested += n; Continue }
    def handleCancel(): Effect = ???

    def handleComplete(): Effect = {
      become(CompleteAfter(subDownstream, firstElement))
      downstream.complete
    }
    def handleError(cause: Throwable): Effect = ???
  }
  // invariant:
  // if (undeliveredElements != 0) => one element currently requested
  def Running(subDownstream: Downstream[I], _undeliveredElements: Int): State = new State {
    var undeliveredElements = _undeliveredElements
    subSource.become(new SyncSource {
      def handleRequestMore(n: Int): Effect = {
        val nothingRequested = undeliveredElements == 0
        undeliveredElements += n

        if (nothingRequested) upstream.requestMore(1) else Continue
      }
      def handleCancel(): Effect = ???
    })

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
    def handleComplete(): Effect = {
      become(Completed)
      subDownstream.complete ~ downstream.complete
    }
    def handleError(cause: Throwable): Effect = ???
  }

  // invariant:
  // upstream and parent stream already completed, substream established but nothing requested yet
  def CompleteAfter(subDownstream: Downstream[I], firstElement: I): State = new RejectNext {
    subSource.become(new SyncSource {
      def handleRequestMore(n: Int): Effect = {
        become(Completed)
        subDownstream.next(firstElement) ~ subDownstream.complete
      }
      def handleCancel(): Effect = ???
    })
    def handleRequestMore(n: Int): Effect = Continue // ignore after completion
    def handleCancel(): Effect = ???

    def handleComplete(): Effect = ???
    def handleError(cause: Throwable): Effect = ???
  }

  // invariant:
  // upstream, parent stream and all substreams completed
  val Completed: State = new RejectNext {
    subSource.become(SubCompleted)
    def handleRequestMore(n: Int): Effect = Continue
    def handleCancel(): Effect = ???

    def handleComplete(): Effect = ???
    def handleError(cause: Throwable): Effect = ???
  }
  val SubCompleted = new SyncSource {
    def handleRequestMore(n: Int): Effect = Continue // ignore
    def handleCancel(): Effect = Continue // ignore
  }
}
