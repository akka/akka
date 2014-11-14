/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

object IteratorInterpreter {
  case class IteratorUpstream[T](input: Iterator[T]) extends DeterministicOp[T, T] {
    private var hasNext = input.hasNext

    override def onPush(elem: T, ctxt: Context[T]): Directive =
      throw new UnsupportedOperationException("IteratorUpstream operates as a source, it cannot be pushed")

    override def onPull(ctxt: Context[T]): Directive = {
      if (!hasNext) ctxt.finish()
      else {
        val elem = input.next()
        hasNext = input.hasNext
        if (!hasNext) ctxt.pushAndFinish(elem)
        else ctxt.push(elem)
      }

    }
  }

  case class IteratorDownstream[T]() extends BoundaryOp with Iterator[T] {
    private var done = false
    private var nextElem: T = _
    private var needsPull = true
    private var lastError: Throwable = null

    override def onPush(elem: Any, ctxt: BoundaryContext): Directive = {
      nextElem = elem.asInstanceOf[T]
      needsPull = false
      ctxt.exit()
    }

    override def onPull(ctxt: BoundaryContext): Directive =
      throw new UnsupportedOperationException("IteratorDownstream operates as a sink, it cannot be pulled")

    override def onUpstreamFinish(ctxt: BoundaryContext): TerminationDirective = {
      done = true
      ctxt.finish()
    }

    override def onFailure(cause: Throwable, ctxt: BoundaryContext): TerminationDirective = {
      done = true
      lastError = cause
      ctxt.finish()
    }

    private def pullIfNeeded(): Unit = {
      if (needsPull) {
        enter().pull() // will eventually result in a finish, or an onPush which exits
      }
    }

    override def hasNext: Boolean = {
      if (!done) pullIfNeeded()
      !(done && needsPull)
    }

    override def next(): T = {
      if (!hasNext) {
        if (lastError != null) throw lastError
        else Iterator.empty.next()
      }
      needsPull = true
      nextElem
    }

  }
}

class IteratorInterpreter[I, O](val input: Iterator[I], val ops: Seq[DeterministicOp[_, _]]) {
  import akka.stream.impl.fusing.IteratorInterpreter._

  private val upstream = IteratorUpstream(input)
  private val downstream = IteratorDownstream[O]()
  private val interpreter = new OneBoundedInterpreter(upstream +: ops.asInstanceOf[Seq[Op[_, _, _, _, _]]] :+ downstream)
  interpreter.init()

  def iterator: Iterator[O] = downstream
}
