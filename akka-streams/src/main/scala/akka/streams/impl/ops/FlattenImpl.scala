package akka.streams.impl.ops

import akka.streams.Operation
import akka.streams.impl._
import Operation.Source

class FlattenImpl[O](upstream: Upstream, downstream: Downstream[O], ctx: ContextEffects) extends DynamicSyncOperation[Source[O]] {
  override def toString: String = "Flatten"

  def initial: State = Waiting

  var undeliveredToDownstream: Int = 0
  var closeAtEnd = false

  def Waiting: State =
    new State {
      def handleRequestMore(n: Int): Effect = {
        become(WaitingForElement)
        assert(undeliveredToDownstream == 0)
        undeliveredToDownstream = n
        upstream.requestMore(1)
      }
      def handleCancel(): Effect = ???

      def handleNext(element: Source[O]): Effect = throw new IllegalStateException("No element requested")
      def handleComplete(): Effect = downstream.complete
      def handleError(cause: Throwable): Effect = downstream.error(cause)
    }

  def WaitingForElement: State =
    new State {
      def handleRequestMore(n: Int): Effect = {
        become(WaitingForElement)
        undeliveredToDownstream += n
        Continue
      }
      def handleCancel(): Effect = ???

      def handleNext(element: Source[O]): Effect = {
        val readSubstream = new Subscribing
        become(readSubstream)
        ctx.subscribeTo(element)(readSubstream.setSubUpstream)
      }
      def handleComplete(): Effect = downstream.complete
      def handleError(cause: Throwable): Effect = downstream.error(cause)
    }

  def createSubSink(subUpstream: Upstream, initialRequest: Int) =
    new SyncSink[O] {
      override def toString: String = "FlattenSubSink"

      override def start(): Effect = subUpstream.requestMore(initialRequest)

      def handleNext(element: O): Effect = {
        undeliveredToDownstream -= 1
        downstream.next(element)
      }

      def handleComplete(): Effect =
        if (closeAtEnd) downstream.complete
        else if (undeliveredToDownstream > 0) {
          become(WaitingForElement)
          upstream.requestMore(1)
        } else {
          become(Waiting)
          Continue
        }

      def handleError(cause: Throwable): Effect = ???
    }

  // invariant:
  // we've got a single subSource that we are currently subscribing to
  class Subscribing extends RejectNext {
    def setSubUpstream(subUpstream: Upstream): SyncSink[O] = {
      become(DeliverSubstreamElements(subUpstream))
      createSubSink(subUpstream, undeliveredToDownstream)
    }

    def handleRequestMore(n: Int): Effect = {
      undeliveredToDownstream += n
      Continue
    }
    def handleCancel(): Effect = ???

    def handleComplete(): Effect = {
      closeAtEnd = true
      Continue
    }
    def handleError(cause: Throwable): Effect = ???

  }
  // invariant:
  // substream is not depleted, all elements have been requested from subUpstream
  def DeliverSubstreamElements(subUpstream: Upstream): State = new RejectNext {
    def handleRequestMore(n: Int): Effect = {
      undeliveredToDownstream += n
      subUpstream.requestMore(n)
    }
    def handleCancel(): Effect = ???

    def handleComplete(): Effect = {
      closeAtEnd = true
      Continue
    }
    def handleError(cause: Throwable): Effect = ???
  }
}
