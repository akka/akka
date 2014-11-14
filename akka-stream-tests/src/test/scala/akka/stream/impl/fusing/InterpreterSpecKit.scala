/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.testkit.AkkaSpec

trait InterpreterSpecKit extends AkkaSpec {

  case object OnComplete
  case object Cancel
  case class OnError(cause: Throwable)
  case class OnNext(elem: Any)
  case object RequestOne

  private[akka] case class Doubler[T]() extends DeterministicOp[T, T] {
    var oneMore: Boolean = false
    var lastElem: T = _

    override def onPush(elem: T, ctxt: Context[T]): Directive = {
      lastElem = elem
      oneMore = true
      ctxt.push(elem)
    }

    override def onPull(ctxt: Context[T]): Directive = {
      if (oneMore) {
        oneMore = false
        ctxt.push(lastElem)
      } else ctxt.pull()
    }
  }

  abstract class TestSetup(ops: Seq[Op[_, _, _, _, _]], forkLimit: Int = 100, overflowToHeap: Boolean = false) {
    private var lastEvent: Set[Any] = Set.empty

    val upstream = new UpstreamProbe
    val downstream = new DownstreamProbe
    val interpreter = new OneBoundedInterpreter(upstream +: ops :+ downstream, forkLimit, overflowToHeap)
    interpreter.init()

    def lastEvents(): Set[Any] = {
      val result = lastEvent
      lastEvent = Set.empty
      result
    }

    class UpstreamProbe extends BoundaryOp {

      override def onDownstreamFinish(ctxt: BoundaryContext): TerminationDirective = {
        lastEvent += Cancel
        ctxt.exit()
      }

      override def onPull(ctxt: BoundaryContext): Directive = {
        lastEvent += RequestOne
        ctxt.exit()
      }

      override def onPush(elem: Any, ctxt: BoundaryContext): Directive =
        throw new UnsupportedOperationException("Cannot push the boundary")

      def onNext(elem: Any): Unit = enter().push(elem)
      def onComplete(): Unit = enter().finish()
      def onError(cause: Throwable): Unit = enter().fail(cause)

    }

    class DownstreamProbe extends BoundaryOp {
      override def onPush(elem: Any, ctxt: BoundaryContext): Directive = {
        lastEvent += OnNext(elem)
        ctxt.exit()
      }

      override def onUpstreamFinish(ctxt: BoundaryContext): TerminationDirective = {
        lastEvent += OnComplete
        ctxt.exit()
      }

      override def onFailure(cause: Throwable, ctxt: BoundaryContext): TerminationDirective = {
        lastEvent += OnError(cause)
        ctxt.exit()
      }

      override def onPull(ctxt: BoundaryContext): Directive =
        throw new UnsupportedOperationException("Cannot pull the boundary")

      def requestOne(): Unit = enter().pull()

      def cancel(): Unit = enter().finish()
    }

  }
}
