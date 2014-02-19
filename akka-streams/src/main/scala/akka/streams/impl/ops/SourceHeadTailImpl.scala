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
    def onSubscribe(upstream: Upstream): (SyncSink[O], Effect) = {
      val next = new WaitingForFirstElement(upstream)
      become(next)
      (next.subSink, upstream.requestMore(1))
    }

    override def handleComplete(): Effect = ???
    override def handleError(cause: Throwable): Effect = ???

    override def handleRequestMore(n: Int): Effect = {
      undeliveredElements += n
      Continue
    }
    override def handleCancel(): Effect = ???
  }
  class WaitingForFirstElement(subUpstream: Upstream) extends RejectNext { outer ⇒
    val subSink = new DynamicSyncSink[O] {
      override def initial: State = WaitingForFirstElement

      def WaitingForFirstElement = new State {
        override def handleNext(element: O): Effect = {
          undeliveredElements -= 1
          downstream.next((element, InternalSource(onSubscribe))) ~ receivedFirst()
        }
        override def handleComplete(): Effect = ??? // FIXME: what, if there is no head!?!
        override def handleError(cause: Throwable): Effect = ???
      }

      def Running(subDownstream: Downstream[O]) = new State {
        override def handleNext(element: O): Effect = subDownstream.next(element)
        override def handleComplete(): Effect = subDownstream.complete
        override def handleError(cause: Throwable): Effect = subDownstream.error(cause)
      }
      val innerSource = new SyncSource {
        override def handleRequestMore(n: Int): Effect = subUpstream.requestMore(n)
        override def handleCancel(): Effect = ???
      }
      def onSubscribe(subDownstream: Downstream[O]): (SyncSource, Effect) = {
        become(Running(subDownstream))
        (innerSource, Continue)
      }
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
