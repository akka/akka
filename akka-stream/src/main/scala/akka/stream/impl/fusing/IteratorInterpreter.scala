/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.event.NoLogging
import akka.stream._
import akka.stream.impl.fusing.GraphInterpreter.{ GraphAssembly, DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic }
import akka.stream.stage._
import java.{ util ⇒ ju }

/**
 * INTERNAL API
 */
private[akka] object IteratorInterpreter {

  final case class IteratorUpstream[T](input: Iterator[T]) extends UpstreamBoundaryStageLogic[T] with OutHandler {
    val out: Outlet[T] = Outlet[T]("IteratorUpstream.out")
    out.id = 0

    private var hasNext = input.hasNext

    def onPull(): Unit = {
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

    setHandler(out, this)

    override def toString = "IteratorUpstream"
  }

  final case class IteratorDownstream[T]() extends DownstreamBoundaryStageLogic[T] with Iterator[T] with InHandler {
    val in: Inlet[T] = Inlet[T]("IteratorDownstream.in")
    in.id = 0

    private var done = false
    private var nextElem: T = _
    private var needsPull = true
    private var lastFailure: Throwable = null

    def onPush(): Unit = {
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

    setHandler(in, this)

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
private[akka] class IteratorInterpreter[I, O](
  val input:  Iterator[I],
  val stages: Seq[GraphStageWithMaterializedValue[FlowShape[_, _], Any]]) {

  import akka.stream.impl.fusing.IteratorInterpreter._

  private val upstream = IteratorUpstream(input)
  private val downstream = IteratorDownstream[O]()

  private def init(): Unit = {
    import GraphInterpreter.Boundary

    var i = 0
    val length = stages.length
    val attributes = Array.fill[Attributes](length)(Attributes.none)
    val ins = Array.ofDim[Inlet[_]](length + 1)
    val inOwners = Array.ofDim[Int](length + 1)
    val outs = Array.ofDim[Outlet[_]](length + 1)
    val outOwners = Array.ofDim[Int](length + 1)
    val stagesArray = Array.ofDim[GraphStageWithMaterializedValue[Shape, Any]](length)

    ins(length) = null
    inOwners(length) = Boundary
    outs(0) = null
    outOwners(0) = Boundary

    val stagesIterator = stages.iterator
    while (stagesIterator.hasNext) {
      val stage = stagesIterator.next()
      stagesArray(i) = stage
      ins(i) = stage.shape.in
      inOwners(i) = i
      outs(i + 1) = stage.shape.out
      outOwners(i + 1) = i
      i += 1
    }
    val assembly = new GraphAssembly(stagesArray, attributes, ins, inOwners, outs, outOwners)

    val (connections, logics) =
      assembly.materialize(Attributes.none, assembly.stages.map(_.module), new ju.HashMap, _ ⇒ ())
    val interpreter = new GraphInterpreter(
      assembly,
      NoMaterializer,
      NoLogging,
      logics,
      connections,
      (_, _, _) ⇒ throw new UnsupportedOperationException("IteratorInterpreter does not support asynchronous events."),
      fuzzingMode = false,
      null)
    interpreter.attachUpstreamBoundary(connections(0), upstream)
    interpreter.attachDownstreamBoundary(connections(length), downstream)
    interpreter.init(null)
  }

  init()

  def iterator: Iterator[O] = downstream
}
