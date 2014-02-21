package akka.streams.impl.ops

import akka.streams.impl._
import akka.streams.Operation.Source

class SourceHeadTailImpl[O](upstream: Upstream, downstream: Downstream[(O, Source[O])], ctx: ContextEffects)
  extends DynamicSyncOperation[Source[O]] {
  def initial = WaitingForRequest

  var undeliveredElements = 0

  def WaitingForRequest = new RejectNext {
    def handleRequestMore(n: Int): Effect = {
      undeliveredElements += n
      become(WaitingForElement)
      upstream.requestMore(1)
    }
    def handleCancel(): Effect = upstream.cancel

    override def handleComplete(): Effect = downstream.complete
    override def handleError(cause: Throwable): Effect = downstream.error(cause)
  }
  def WaitingForElement = new State {
    def handleRequestMore(n: Int): Effect = {
      undeliveredElements += n
      Continue
    }
    def handleCancel(): Effect = upstream.cancel

    def handleNext(element: Source[O]): Effect = {
      val nextState = new WaitingForSubscription()
      become(nextState)
      ctx.subscribeTo(element)(nextState.onSubscribe)
    }
    def handleComplete(): Effect = downstream.complete
    // FIXME: how to handle substream errors preventing an element to be sent?
    def handleError(cause: Throwable): Effect = downstream.error(cause)
  }
  class WaitingForSubscription() extends RejectNext {
    var closeAfterNext = false
    def onSubscribe(upstream: Upstream): SyncSink[O] = {
      val next = new WaitingForFirstElement(upstream, closeAfterNext)
      become(next)
      next.subSink
    }

    override def handleComplete(): Effect = {
      closeAfterNext = true
      Continue
    }
    override def handleError(cause: Throwable): Effect = ???

    override def handleRequestMore(n: Int): Effect = {
      undeliveredElements += n
      Continue
    }
    override def handleCancel(): Effect = ???
  }
  class WaitingForFirstElement(subUpstream: Upstream, closeDownstreamAfterFirst: Boolean) extends RejectNext { outer ⇒
    val subSink = new DynamicSyncSink[O] {
      override def start(): Effect = subUpstream.requestMore(1)

      var closing = false
      override def initial: State = WaitingForFirstElement

      def WaitingForFirstElement = new State {
        override def handleNext(element: O): Effect = {
          undeliveredElements -= 1
          become(Subscribing)
          downstream.next((element, ctx.internalProducer(onSubscribe))) ~ (if (closeDownstreamAfterFirst) downstream.complete else Continue) ~ receivedFirst()
        }
        override def handleComplete(): Effect = ??? // FIXME: what, if there is no head!?!
        override def handleError(cause: Throwable): Effect = ???
      }
      def Subscribing = new State {
        override def handleNext(element: O): Effect = ???
        override def handleComplete(): Effect = { closing = true; Continue }
        override def handleError(cause: Throwable): Effect = ???
      }

      def Running(subDownstream: Downstream[O]) = new State {
        override def handleNext(element: O): Effect = subDownstream.next(element)
        override def handleComplete(): Effect = subDownstream.complete
        override def handleError(cause: Throwable): Effect = subDownstream.error(cause)
      }
      def createInnerSource(init: Effect) = new SyncSource {
        override def start(): Effect = init

        override def handleRequestMore(n: Int): Effect = subUpstream.requestMore(n)
        override def handleCancel(): Effect = ???
      }
      def onSubscribe(subDownstream: Downstream[O]): SyncSource =
        if (!closing) {
          become(Running(subDownstream))
          createInnerSource(Continue)
        } else createInnerSource(subDownstream.complete)
    }

    def receivedFirst(): Effect =
      if (undeliveredElements > 0) {
        become(WaitingForElement)
        upstream.requestMore(1)
      } else {
        become(WaitingForRequest)
        Continue
      }

    override def handleRequestMore(n: Int): Effect = {
      undeliveredElements += n
      Continue
    }
    override def handleCancel(): Effect = ???

    override def handleComplete(): Effect = ???
    override def handleError(cause: Throwable): Effect = ???
  }
}
