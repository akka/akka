/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.event.{ NoLogging }
import akka.stream._
import akka.stream.impl.fusing.GraphInterpreter.{ GraphAssembly, DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic }
import akka.stream.stage.AbstractStage.PushPullGraphStage
import akka.stream.stage._
import java.{ util ⇒ ju }

/**
 * INTERNAL API
 */
private[akka] object IteratorInterpreter {

  final case class IteratorUpstream[T](input: Iterator[T]) extends UpstreamBoundaryStageLogic[T] {
    val out: Outlet[T] = Outlet[T]("IteratorUpstream.out")
    out.id = 0

    private var hasNext = input.hasNext

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (!hasNext) complete(out)
        else {
          val elem = input.next()
          hasNext = input.hasNext
          if (!hasNext) {
            push(out, elem)
            complete(out)
          } else push(out, elem)
        }
      }

      override def onDownstreamFinish(): Unit = ()
    })

    override def toString = "IteratorUpstream"
  }

  final case class IteratorDownstream[T]() extends DownstreamBoundaryStageLogic[T] with Iterator[T] {
    val in: Inlet[T] = Inlet[T]("IteratorDownstream.in")
    in.id = 0

    private var done = false
    private var nextElem: T = _
    private var needsPull = true
    private var lastFailure: Throwable = null

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        nextElem = grab(in)
        needsPull = false
      }

      override def onUpstreamFinish(): Unit = {
        done = true
      }

      override def onUpstreamFailure(cause: Throwable): Unit = {
        done = true
        lastFailure = cause
      }
    })

    private def pullIfNeeded(): Unit = {
      if (needsPull) {
        pull(in)
        interpreter.execute(Int.MaxValue)
      }
    }

    override def hasNext: Boolean = {
      if (!done) pullIfNeeded()
      !(done && needsPull) || (lastFailure ne null)
    }

    override def next(): T = {
      if (lastFailure ne null) {
        val e = lastFailure
        lastFailure = null
        throw e
      } else if (!hasNext)
        Iterator.empty.next()
      else {
        needsPull = true
        nextElem
      }
    }

    // don't let toString consume the iterator
    override def toString: String = "IteratorDownstream"
  }

}

/**
 * INTERNAL API
 */
private[akka] class IteratorInterpreter[I, O](val input: Iterator[I], val ops: Seq[PushPullStage[_, _]]) {
  import akka.stream.impl.fusing.IteratorInterpreter._

  private val upstream = IteratorUpstream(input)
  private val downstream = IteratorDownstream[O]()

  private def init(): Unit = {
    import GraphInterpreter.Boundary

    var i = 0
    val length = ops.length
    val attributes = Array.fill[Attributes](ops.length)(Attributes.none)
    val ins = Array.ofDim[Inlet[_]](length + 1)
    val inOwners = Array.ofDim[Int](length + 1)
    val outs = Array.ofDim[Outlet[_]](length + 1)
    val outOwners = Array.ofDim[Int](length + 1)
    val stages = Array.ofDim[GraphStageWithMaterializedValue[Shape, Any]](length)

    ins(ops.length) = null
    inOwners(ops.length) = Boundary
    outs(0) = null
    outOwners(0) = Boundary

    val opsIterator = ops.iterator
    while (opsIterator.hasNext) {
      val op = opsIterator.next().asInstanceOf[Stage[Any, Any]]
      val stage = new PushPullGraphStage((_) ⇒ op, Attributes.none)
      stages(i) = stage
      ins(i) = stage.shape.in
      inOwners(i) = i
      outs(i + 1) = stage.shape.out
      outOwners(i + 1) = i
      i += 1
    }
    val assembly = new GraphAssembly(stages, attributes, ins, inOwners, outs, outOwners)

    val (inHandlers, outHandlers, logics) =
      assembly.materialize(Attributes.none, assembly.stages.map(_.module), new ju.HashMap, _ ⇒ ())
    val interpreter = new GraphInterpreter(
      assembly,
      NoMaterializer,
      NoLogging,
      inHandlers,
      outHandlers,
      logics,
      (_, _, _) ⇒ throw new UnsupportedOperationException("IteratorInterpreter does not support asynchronous events."),
      fuzzingMode = false)
    interpreter.attachUpstreamBoundary(0, upstream)
    interpreter.attachDownstreamBoundary(ops.length, downstream)
    interpreter.init(null)
  }

  init()

  def iterator: Iterator[O] = downstream
}
