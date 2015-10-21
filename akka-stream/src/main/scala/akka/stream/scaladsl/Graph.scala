/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Stages.{ MaterializingStageFactory, StageModule }
import akka.stream.impl._
import akka.stream.impl.StreamLayout._
import akka.stream._
import Attributes.name
import akka.stream.stage.{ OutHandler, InHandler, GraphStageLogic, GraphStage }
import scala.annotation.unchecked.uncheckedVariance
import scala.annotation.tailrec
import scala.collection.immutable

object Merge {
  /**
   * Create a new `Merge` with the specified number of input ports.
   *
   * @param inputPorts number of input ports
   * @param eagerClose if true, the merge will complete as soon as one of its inputs completes.
   */
  def apply[T](inputPorts: Int, eagerClose: Boolean = false): Merge[T] = new Merge(inputPorts, eagerClose)

}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking randomly when several have elements ready).
 *
 * '''Emits when''' one of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
 *
 * '''Cancels when''' downstream cancels
 */
class Merge[T] private (val inputPorts: Int, val eagerClose: Boolean) extends GraphStage[UniformFanInShape[T, T]] {
  val in: immutable.IndexedSeq[Inlet[T]] = Vector.tabulate(inputPorts)(i ⇒ Inlet[T]("Merge.in" + i))
  val out: Outlet[T] = Outlet[T]("Merge.out")
  override val shape: UniformFanInShape[T, T] = UniformFanInShape(out, in: _*)

  override def createLogic: GraphStageLogic = new GraphStageLogic(shape) {
    private var initialized = false

    private val pendingQueue = Array.ofDim[Inlet[T]](inputPorts)
    private var pendingHead = 0
    private var pendingTail = 0

    private var runningUpstreams = inputPorts
    private def upstreamsClosed = runningUpstreams == 0

    private def pending: Boolean = pendingHead != pendingTail

    private def enqueue(in: Inlet[T]): Unit = {
      pendingQueue(pendingTail % inputPorts) = in
      pendingTail += 1
    }

    private def dequeueAndDispatch(): Unit = {
      val in = pendingQueue(pendingHead % inputPorts)
      pendingHead += 1
      push(out, grab(in))
      if (upstreamsClosed && !pending) completeStage()
      else tryPull(in)
    }

    in.foreach { i ⇒
      setHandler(i, new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(out)) {
            if (!pending) {
              push(out, grab(i))
              tryPull(i)
            }
          } else enqueue(i)
        }

        override def onUpstreamFinish() =
          if (eagerClose) {
            in.foreach(cancel)
            runningUpstreams = 0
            if (!pending) completeStage()
          } else {
            runningUpstreams -= 1
            if (upstreamsClosed && !pending) completeStage()
          }
      })
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (!initialized) {
          initialized = true
          in.foreach(tryPull)
        } else if (pending)
          dequeueAndDispatch()
      }
    })
  }

  override def toString = "Merge"
}

object MergePreferred {
  import FanInShape._
  final class MergePreferredShape[T](val secondaryPorts: Int, _init: Init[T]) extends UniformFanInShape[T, T](secondaryPorts, _init) {
    def this(secondaryPorts: Int, name: String) = this(secondaryPorts, Name[T](name))
    override protected def construct(init: Init[T]): FanInShape[T] = new MergePreferredShape(secondaryPorts, init)
    override def deepCopy(): MergePreferredShape[T] = super.deepCopy().asInstanceOf[MergePreferredShape[T]]

    val preferred = newInlet[T]("preferred")
  }

  /**
   * Create a new `MergePreferred` with the specified number of secondary input ports.
   *
   * @param secondaryPorts number of secondary input ports
   * @param eagerClose if true, the merge will complete as soon as one of its inputs completes.
   */
  def apply[T](secondaryPorts: Int, eagerClose: Boolean = false): MergePreferred[T] = new MergePreferred(secondaryPorts, eagerClose)
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * A `MergePreferred` has one `out` port, one `preferred` input port and 0 or more secondary `in` ports.
 *
 * '''Emits when''' one of the inputs has an element available, preferring
 * a specified input if multiple have elements available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete
 *
 * '''Cancels when''' downstream cancels
 *
 * A `Broadcast` has one `in` port and 2 or more `out` ports.
 */
class MergePreferred[T] private (val secondaryPorts: Int, val eagerClose: Boolean) extends GraphStage[MergePreferred.MergePreferredShape[T]] {
  override val shape: MergePreferred.MergePreferredShape[T] =
    new MergePreferred.MergePreferredShape(secondaryPorts, "MergePreferred")

  def in(id: Int): Inlet[T] = shape.in(id)
  def out: Outlet[T] = shape.out
  def preferred: Inlet[T] = shape.preferred

  // FIXME: Factor out common stuff with Merge
  override def createLogic: GraphStageLogic = new GraphStageLogic(shape) {
    private var initialized = false

    private val pendingQueue = Array.ofDim[Inlet[T]](secondaryPorts)
    private var pendingHead = 0
    private var pendingTail = 0

    private var runningUpstreams = secondaryPorts + 1
    private def upstreamsClosed = runningUpstreams == 0

    private def pending: Boolean = pendingHead != pendingTail
    private def priority: Boolean = isAvailable(preferred)

    private def enqueue(in: Inlet[T]): Unit = {
      pendingQueue(pendingTail % secondaryPorts) = in
      pendingTail += 1
    }

    private def dequeueAndDispatch(): Unit = {
      val in = pendingQueue(pendingHead % secondaryPorts)
      pendingHead += 1
      push(out, grab(in))
      if (upstreamsClosed && !pending && !priority) completeStage()
      else tryPull(in)
    }

    // FIXME: slow iteration, try to make in a vector and inject into shape instead
    (0 until secondaryPorts).map(in).foreach { i ⇒
      setHandler(i, new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(out)) {
            if (!pending) {
              push(out, grab(i))
              tryPull(i)
            }
          } else enqueue(i)
        }

        override def onUpstreamFinish() =
          if (eagerClose) {
            (0 until secondaryPorts).foreach(i ⇒ cancel(in(i)))
            cancel(preferred)
            runningUpstreams = 0
            if (!pending) completeStage()
          } else {
            runningUpstreams -= 1
            if (upstreamsClosed && !pending && !priority) completeStage()
          }
      })
    }

    setHandler(preferred, new InHandler {
      override def onPush() = {
        if (isAvailable(out)) {
          push(out, grab(preferred))
          tryPull(preferred)
        }
      }

      override def onUpstreamFinish() =
        if (eagerClose) {
          (0 until secondaryPorts).foreach(i ⇒ cancel(in(i)))
          runningUpstreams = 0
          if (!pending) completeStage()
        } else {
          runningUpstreams -= 1
          if (upstreamsClosed && !pending && !priority) completeStage()
        }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (!initialized) {
          initialized = true
          // FIXME: slow iteration, try to make in a vector and inject into shape instead
          tryPull(preferred)
          (0 until secondaryPorts).map(in).foreach(tryPull)
        } else if (priority) {
          push(out, grab(preferred))
          tryPull(preferred)
        } else if (pending)
          dequeueAndDispatch()
      }
    })
  }
}

object Broadcast {
  /**
   * Create a new `Broadcast` with the specified number of output ports.
   *
   * @param outputPorts number of output ports
   * @param eagerCancel if true, broadcast cancels upstream if any of its downstreams cancel.
   */
  def apply[T](outputPorts: Int, eagerCancel: Boolean = false): Broadcast[T] = {
    new Broadcast(outputPorts, eagerCancel)
  }
}

/**
 * Fan-out the stream to several streams emitting each incoming upstream element to all downstream consumers.
 * It will not shut down until the subscriptions for at least two downstream subscribers have been established.
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' any of the outputs backpressure
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when'''
 *   If eagerCancel is enabled: when any downstream cancels; otherwise: when all downstreams cancel
 *
 */
class Broadcast[T](private val outputPorts: Int, eagerCancel: Boolean) extends GraphStage[UniformFanOutShape[T, T]] {
  val in: Inlet[T] = Inlet[T]("Broadast.in")
  val out: immutable.IndexedSeq[Outlet[T]] = Vector.tabulate(outputPorts)(i ⇒ Outlet[T]("Broadcast.out" + i))
  override val shape: UniformFanOutShape[T, T] = UniformFanOutShape(in, out: _*)

  override def createLogic: GraphStageLogic = new GraphStageLogic(shape) {
    private var pendingCount = outputPorts
    private val pending = Array.fill[Boolean](outputPorts)(true)
    private var downstreamsRunning = outputPorts

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        pendingCount = downstreamsRunning
        val elem = grab(in)

        var idx = 0
        val itr = out.iterator

        while (itr.hasNext) {
          val o = itr.next()
          val i = idx
          if (!isClosed(o)) {
            push(o, elem)
            pending(i) = true
          }
          idx += 1
        }
      }
    })

    private def tryPull(): Unit =
      if (pendingCount == 0 && !hasBeenPulled(in)) pull(in)

    {
      var idx = 0
      val itr = out.iterator
      while (itr.hasNext) {
        val out = itr.next()
        val i = idx
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            pending(i) = false
            pendingCount -= 1
            tryPull()
          }

          override def onDownstreamFinish() = {
            if (eagerCancel) completeStage()
            else {
              downstreamsRunning -= 1
              if (downstreamsRunning == 0) completeStage()
              else if (pending(i)) {
                pending(i) = false
                pendingCount -= 1
                tryPull()
              }
            }
          }
        })
        idx += 1
      }
    }

  }

  override def toString = "Broadcast"

}

object Balance {
  /**
   * Create a new `Balance` with the specified number of output ports.
   *
   * @param outputPorts number of output ports
   * @param waitForAllDownstreams if you use `waitForAllDownstreams = true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element,
   *   default value is `false`
   */
  def apply[T](outputPorts: Int, waitForAllDownstreams: Boolean = false): Balance[T] = {
    new Balance(outputPorts, waitForAllDownstreams)
  }
}

/**
 * Fan-out the stream to several streams. Each upstream element is emitted to the first available downstream consumer.
 * It will not shut down until the subscriptions
 * for at least two downstream subscribers have been established.
 *
 * A `Balance` has one `in` port and 2 or more `out` ports.
 *
 * '''Emits when''' any of the outputs stops backpressuring; emits the element to the first available output
 *
 * '''Backpressures when''' all of the outputs backpressure
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when''' all downstreams cancel
 */
class Balance[T](val outputPorts: Int, waitForAllDownstreams: Boolean) extends GraphStage[UniformFanOutShape[T, T]] {
  val in: Inlet[T] = Inlet[T]("Balance.in")
  val out: immutable.IndexedSeq[Outlet[T]] = Vector.tabulate(outputPorts)(i ⇒ Outlet[T]("Balance.out" + i))
  override val shape: UniformFanOutShape[T, T] = UniformFanOutShape[T, T](in, out: _*)

  override def createLogic: GraphStageLogic = new GraphStageLogic(shape) {
    private val pendingQueue = Array.ofDim[Outlet[T]](outputPorts)
    private var pendingHead: Int = 0
    private var pendingTail: Int = 0

    private var needDownstreamPulls: Int = if (waitForAllDownstreams) outputPorts else 0
    private var downstreamsRunning: Int = outputPorts

    private def noPending: Boolean = pendingHead == pendingTail
    private def enqueue(out: Outlet[T]): Unit = {
      pendingQueue(pendingTail % outputPorts) = out
      pendingTail += 1
    }
    private def dequeueAndDispatch(): Unit = {
      val out = pendingQueue(pendingHead % outputPorts)
      pendingHead += 1
      push(out, grab(in))
      if (!noPending) pull(in)
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = dequeueAndDispatch()
    })

    out.foreach { o ⇒
      setHandler(o, new OutHandler {
        private var hasPulled = false

        override def onPull(): Unit = {
          if (!hasPulled) {
            hasPulled = true
            if (needDownstreamPulls > 0) needDownstreamPulls -= 1
          }

          if (needDownstreamPulls == 0) {
            if (isAvailable(in)) {
              if (noPending) {
                push(o, grab(in))
              }
            } else {
              if (!hasBeenPulled(in)) pull(in)
              enqueue(o)
            }
          } else enqueue(o)
        }

        override def onDownstreamFinish() = {
          downstreamsRunning -= 1
          if (downstreamsRunning == 0) completeStage()
          else if (!hasPulled && needDownstreamPulls > 0) {
            needDownstreamPulls -= 1
            if (needDownstreamPulls == 0 && !hasBeenPulled(in)) pull(in)
          }
        }
      })
    }
  }

  override def toString = "Balance"
}

object Zip {
  /**
   * Create a new `Zip`.
   */
  def apply[A, B](): Zip[A, B] = new Zip()
}

/**
 * Combine the elements of 2 streams into a stream of tuples.
 *
 * A `Zip` has a `left` and a `right` input port and one `out` port
 *
 * '''Emits when''' all of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' any upstream completes
 *
 * '''Cancels when''' downstream cancels
 */
class Zip[A, B] extends ZipWith2[A, B, (A, B)](Pair.apply) {
  override def toString = "Zip"
}

/**
 * Combine the elements of multiple streams into a stream of combined elements using a combiner function.
 *
 * '''Emits when''' all of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' any upstream completes
 *
 * '''Cancels when''' downstream cancels
 */
object ZipWith extends ZipWithApply

/**
 * Takes a stream of pair elements and splits each pair to two output streams.
 *
 * An `Unzip` has one `in` port and one `left` and one `right` output port.
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' any of the outputs backpressures
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when''' any downstream cancels
 */
object Unzip {

  private final val _identity: Any ⇒ Any = a ⇒ a

  /**
   * Create a new `Unzip`.
   */
  def apply[A, B](): Unzip[A, B] = {
    new Unzip()
  }
}

/**
 * Combine the elements of multiple streams into a stream of the combined elements.
 */
class Unzip[A, B]() extends UnzipWith2[(A, B), A, B](identity) {
  override def toString = "Unzip"
}

/**
 * Transforms each element of input stream into multiple streams using a splitter function.
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' any of the outputs backpressures
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when''' any downstream cancels
 */
object UnzipWith extends UnzipWithApply

object Concat {
  /**
   * Create a new `Concat`.
   */
  def apply[T](inputCount: Int = 2): Concat[T] = {
    new Concat(inputCount)
  }
}

/**
 * Takes multiple streams and outputs one stream formed from the input streams
 * by first emitting all of the elements from the first stream and then emitting
 * all of the elements from the second stream, etc.
 *
 * A `Concat` has one `first` port, one `second` port and one `out` port.
 *
 * '''Emits when''' the current stream has an element available; if the current input completes, it tries the next one
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete
 *
 * '''Cancels when''' downstream cancels
 */
class Concat[T](inputCount: Int) extends GraphStage[UniformFanInShape[T, T]] {
  val in: immutable.IndexedSeq[Inlet[T]] = Vector.tabulate(inputCount)(i ⇒ Inlet[T]("Concat.in" + i))
  val out: Outlet[T] = Outlet[T]("Concat.out")
  override val shape: UniformFanInShape[T, T] = UniformFanInShape(out, in: _*)

  override def createLogic = new GraphStageLogic(shape) {
    var activeStream: Int = 0

    {
      var idxx = 0
      val itr = in.iterator
      while (itr.hasNext) {
        val i = itr.next()
        val idx = idxx
        setHandler(i, new InHandler {
          override def onPush() = {
            push(out, grab(i))
          }

          override def onUpstreamFinish() = {
            if (idx == activeStream) {
              activeStream += 1
              // Skip closed inputs
              while (activeStream < inputCount && isClosed(in(activeStream))) activeStream += 1
              if (activeStream == inputCount) completeStage()
              else if (isAvailable(out)) pull(in(activeStream))
            }
          }
        })
        idxx += 1
      }
    }

    setHandler(out, new OutHandler {
      override def onPull() = pull(in(activeStream))
    })

  }
}

object FlowGraph extends GraphApply {

  class Builder[+M] private[stream] () {
    private var moduleInProgress: Module = EmptyModule

    def addEdge[A1, A >: A1, B, B1 >: B, M2](from: Outlet[A1], via: Graph[FlowShape[A, B], M2], to: Inlet[B1]): Unit = {
      val flowCopy = via.module.carbonCopy
      moduleInProgress =
        moduleInProgress
          .compose(flowCopy)
          .wire(from, flowCopy.shape.inlets.head)
          .wire(flowCopy.shape.outlets.head, to)
    }

    def addEdge[T, U >: T](from: Outlet[T], to: Inlet[U]): Unit = {
      moduleInProgress = moduleInProgress.wire(from, to)
    }

    /**
     * Import a graph into this module, performing a deep copy, discarding its
     * materialized value and returning the copied Ports that are now to be
     * connected.
     */
    def add[S <: Shape](graph: Graph[S, _]): S = {
      if (StreamLayout.Debug) StreamLayout.validate(graph.module)
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.compose(copy)
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    /**
     * INTERNAL API.
     *
     * This is only used by the materialization-importing apply methods of Source,
     * Flow, Sink and Graph.
     */
    private[stream] def add[S <: Shape, A](graph: Graph[S, _], transform: (A) ⇒ Any): S = {
      if (StreamLayout.Debug) StreamLayout.validate(graph.module)
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.compose(copy.transformMaterializedValue(transform.asInstanceOf[Any ⇒ Any]))
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    /**
     * INTERNAL API.
     *
     * This is only used by the materialization-importing apply methods of Source,
     * Flow, Sink and Graph.
     */
    private[stream] def add[S <: Shape, A, B](graph: Graph[S, _], combine: (A, B) ⇒ Any): S = {
      if (StreamLayout.Debug) StreamLayout.validate(graph.module)
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.compose(copy, combine)
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    def add[T](s: Source[T, _]): Outlet[T] = add(s: Graph[SourceShape[T], _]).outlet
    def add[T](s: Sink[T, _]): Inlet[T] = add(s: Graph[SinkShape[T], _]).inlet

    /**
     * Returns an [[Outlet]] that gives access to the materialized value of this graph. Once the graph is materialized
     * this outlet will emit exactly one element which is the materialized value. It is possible to expose this
     * outlet as an externally accessible outlet of a [[Source]], [[Sink]], [[Flow]] or [[BidiFlow]].
     *
     * It is possible to call this method multiple times to get multiple [[Outlet]] instances if necessary. All of
     * the outlets will emit the materialized value.
     *
     * Be careful to not to feed the result of this outlet to a stage that produces the materialized value itself (for
     * example to a [[Sink#fold]] that contributes to the materialized value) since that might lead to an unresolvable
     * dependency cycle.
     *
     * @return The outlet that will emit the materialized value.
     */
    def materializedValue: Outlet[M @uncheckedVariance] = {
      val module = new MaterializedValueSource[Any]
      moduleInProgress = moduleInProgress.compose(module)
      module.shape.outlet.asInstanceOf[Outlet[M]]
    }

    private[stream] def andThen(port: OutPort, op: StageModule): Unit = {
      moduleInProgress =
        moduleInProgress
          .compose(op)
          .wire(port, op.inPort)
    }

    private[stream] def buildRunnable[Mat](): RunnableGraph[Mat] = {
      if (!moduleInProgress.isRunnable) {
        throw new IllegalArgumentException(
          "Cannot build the RunnableGraph because there are unconnected ports: " +
            (moduleInProgress.outPorts ++ moduleInProgress.inPorts).mkString(", "))
      }
      new RunnableGraph(moduleInProgress.nest())
    }

    private[stream] def buildSource[T, Mat](outlet: Outlet[T]): Source[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalArgumentException("Cannot build the Source since no ports remain open")
      if (!moduleInProgress.isSource)
        throw new IllegalArgumentException(
          s"Cannot build Source with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalArgumentException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      new Source(moduleInProgress.replaceShape(SourceShape(outlet)).nest())
    }

    private[stream] def buildFlow[In, Out, Mat](inlet: Inlet[In], outlet: Outlet[Out]): Flow[In, Out, Mat] = {
      if (!moduleInProgress.isFlow)
        throw new IllegalArgumentException(
          s"Cannot build Flow with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalArgumentException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalArgumentException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Flow(moduleInProgress.replaceShape(FlowShape(inlet, outlet)).nest())
    }

    private[stream] def buildBidiFlow[I1, O1, I2, O2, Mat](shape: BidiShape[I1, O1, I2, O2]): BidiFlow[I1, O1, I2, O2, Mat] = {
      if (!moduleInProgress.isBidiFlow)
        throw new IllegalArgumentException(
          s"Cannot build BidiFlow with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.toSet != shape.outlets.toSet)
        throw new IllegalArgumentException(s"provided Outlets [${shape.outlets.mkString(",")}] does not equal the module’s open Outlets [${moduleInProgress.outPorts.mkString(",")}]")
      if (moduleInProgress.inPorts.toSet != shape.inlets.toSet)
        throw new IllegalArgumentException(s"provided Inlets [${shape.inlets.mkString(",")}] does not equal the module’s open Inlets [${moduleInProgress.inPorts.mkString(",")}]")
      new BidiFlow(moduleInProgress.replaceShape(shape).nest())
    }

    private[stream] def buildSink[T, Mat](inlet: Inlet[T]): Sink[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalArgumentException("Cannot build the Sink since no ports remain open")
      if (!moduleInProgress.isSink)
        throw new IllegalArgumentException(
          s"Cannot build Sink with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalArgumentException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Sink(moduleInProgress.replaceShape(SinkShape(inlet)).nest())
    }

    private[stream] def module: Module = moduleInProgress

    /** Converts this Scala DSL element to it's Java DSL counterpart. */
    def asJava: javadsl.FlowGraph.Builder[M] = new javadsl.FlowGraph.Builder()(this)

  }

  object Implicits {

    @tailrec
    private[stream] def findOut[I, O](b: Builder[_], junction: UniformFanOutShape[I, O], n: Int): Outlet[O] = {
      if (n == junction.outArray.length)
        throw new IllegalArgumentException(s"no more outlets free on $junction")
      else if (b.module.downstreams.contains(junction.out(n))) findOut(b, junction, n + 1)
      else junction.out(n)
    }

    @tailrec
    private[stream] def findIn[I, O](b: Builder[_], junction: UniformFanInShape[I, O], n: Int): Inlet[I] = {
      if (n == junction.inSeq.length)
        throw new IllegalArgumentException(s"no more inlets free on $junction")
      else if (b.module.upstreams.contains(junction.in(n))) findIn(b, junction, n + 1)
      else junction.in(n)
    }

    trait CombinerBase[T] extends Any {
      def importAndGetPort(b: Builder[_]): Outlet[T]

      def ~>[U >: T](to: Inlet[U])(implicit b: Builder[_]): Unit = {
        b.addEdge(importAndGetPort(b), to)
      }

      def ~>[Out](via: Graph[FlowShape[T, Out], Any])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        val s = b.add(via)
        b.addEdge(importAndGetPort(b), s.inlet)
        s.outlet
      }

      def ~>[Out](junction: UniformFanInShape[T, Out])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        def bind(n: Int): Unit = {
          if (n == junction.inSeq.length)
            throw new IllegalArgumentException(s"no more inlets free on $junction")
          else if (b.module.upstreams.contains(junction.in(n))) bind(n + 1)
          else b.addEdge(importAndGetPort(b), junction.in(n))
        }
        bind(0)
        junction.out
      }

      def ~>[Out](junction: UniformFanOutShape[T, Out])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        b.addEdge(importAndGetPort(b), junction.in)
        try findOut(b, junction, 0)
        catch {
          case e: IllegalArgumentException ⇒ new DisabledPortOps(e.getMessage)
        }
      }

      def ~>[Out](flow: FlowShape[T, Out])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        b.addEdge(importAndGetPort(b), flow.inlet)
        flow.outlet
      }

      def ~>(to: Graph[SinkShape[T], _])(implicit b: Builder[_]): Unit = {
        b.addEdge(importAndGetPort(b), b.add(to).inlet)
      }

      def ~>(to: SinkShape[T])(implicit b: Builder[_]): Unit = {
        b.addEdge(importAndGetPort(b), to.inlet)
      }
    }

    trait ReverseCombinerBase[T] extends Any {
      def importAndGetPortReverse(b: Builder[_]): Inlet[T]

      def <~[U <: T](from: Outlet[U])(implicit b: Builder[_]): Unit = {
        b.addEdge(from, importAndGetPortReverse(b))
      }

      def <~[In](via: Graph[FlowShape[In, T], _])(implicit b: Builder[_]): ReversePortOps[In] = {
        val s = b.add(via)
        b.addEdge(s.outlet, importAndGetPortReverse(b))
        s.inlet
      }

      def <~[In](junction: UniformFanOutShape[In, T])(implicit b: Builder[_]): ReversePortOps[In] = {
        def bind(n: Int): Unit = {
          if (n == junction.outArray.length)
            throw new IllegalArgumentException(s"no more outlets free on $junction")
          else if (b.module.downstreams.contains(junction.out(n))) bind(n + 1)
          else b.addEdge(junction.out(n), importAndGetPortReverse(b))
        }
        bind(0)
        junction.in
      }

      def <~[In](junction: UniformFanInShape[In, T])(implicit b: Builder[_]): ReversePortOps[In] = {
        b.addEdge(junction.out, importAndGetPortReverse(b))
        try findIn(b, junction, 0)
        catch {
          case e: IllegalArgumentException ⇒ new DisabledReversePortOps(e.getMessage)
        }
      }

      def <~[In](flow: FlowShape[In, T])(implicit b: Builder[_]): ReversePortOps[In] = {
        b.addEdge(flow.outlet, importAndGetPortReverse(b))
        flow.inlet
      }

      def <~(from: Graph[SourceShape[T], _])(implicit b: Builder[_]): Unit = {
        b.addEdge(b.add(from).outlet, importAndGetPortReverse(b))
      }

      def <~(from: SourceShape[T])(implicit b: Builder[_]): Unit = {
        b.addEdge(from.outlet, importAndGetPortReverse(b))
      }
    }

    // Although Mat is always Unit, it cannot be removed as a type parameter, otherwise the "override type"
    // won't work below
    class PortOps[Out, Mat](val outlet: Outlet[Out], b: Builder[_]) extends FlowOps[Out, Mat] with CombinerBase[Out] {
      override type Repr[+O, +M] = PortOps[O, M] @uncheckedVariance

      override def withAttributes(attr: Attributes): Repr[Out, Mat] =
        throw new UnsupportedOperationException("Cannot set attributes on chained ops from a junction output port")

      override private[scaladsl] def andThen[U](op: StageModule): Repr[U, Mat] = {
        b.andThen(outlet, op)
        new PortOps(op.shape.outlet.asInstanceOf[Outlet[U]], b)
      }

      override private[scaladsl] def andThenMat[U, Mat2](op: MaterializingStageFactory): Repr[U, Mat2] = {
        // We don't track materialization here
        b.andThen(outlet, op)
        new PortOps(op.shape.outlet.asInstanceOf[Outlet[U]], b)
      }

      override def importAndGetPort(b: Builder[_]): Outlet[Out] = outlet

      override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T, Mat] =
        super.~>(flow)(b).asInstanceOf[Repr[T, Mat]]

      override def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(combine: (Mat, Mat2) ⇒ Mat3) =
        throw new UnsupportedOperationException("Cannot use viaMat on a port")
    }

    class DisabledPortOps[Out, Mat](msg: String) extends PortOps[Out, Mat](null, null) {
      override def importAndGetPort(b: Builder[_]): Outlet[Out] = throw new IllegalArgumentException(msg)

      override def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(combine: (Mat, Mat2) ⇒ Mat3) =
        throw new IllegalArgumentException(msg)
    }

    implicit class ReversePortOps[In](val inlet: Inlet[In]) extends ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = inlet
    }

    class DisabledReversePortOps[In](msg: String) extends ReversePortOps[In](null) {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = throw new IllegalArgumentException(msg)
    }

    implicit class FanInOps[In, Out](val j: UniformFanInShape[In, Out]) extends AnyVal with CombinerBase[Out] with ReverseCombinerBase[In] {
      override def importAndGetPort(b: Builder[_]): Outlet[Out] = j.out
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = findIn(b, j, 0)
    }

    implicit class FanOutOps[In, Out](val j: UniformFanOutShape[In, Out]) extends AnyVal with ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = j.in
    }

    implicit class SinkArrow[T](val s: Graph[SinkShape[T], _]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[T] = b.add(s).inlet
    }

    implicit class SinkShapeArrow[T](val s: SinkShape[T]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[T] = s.inlet
    }

    implicit class FlowShapeArrow[I, O](val f: FlowShape[I, O]) extends AnyVal with ReverseCombinerBase[I] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[I] = f.inlet

      def <~>[I2, O2, Mat](bidi: Graph[BidiShape[O, O2, I2, I], Mat])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        val shape = b.add(bidi)
        b.addEdge(f.outlet, shape.in1)
        b.addEdge(shape.out2, f.inlet)
        shape
      }

      def <~>[I2, O2](bidi: BidiShape[O, O2, I2, I])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        b.addEdge(f.outlet, bidi.in1)
        b.addEdge(bidi.out2, f.inlet)
        bidi
      }

      def <~>[M](flow: Graph[FlowShape[O, I], M])(implicit b: Builder[_]): Unit = {
        val shape = b.add(flow)
        b.addEdge(shape.outlet, f.inlet)
        b.addEdge(f.outlet, shape.inlet)
      }
    }

    implicit class FlowArrow[I, O, M](val f: Graph[FlowShape[I, O], M]) extends AnyVal {
      def <~>[I2, O2, Mat](bidi: Graph[BidiShape[O, O2, I2, I], Mat])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        val shape = b.add(bidi)
        val flow = b.add(f)
        b.addEdge(flow.outlet, shape.in1)
        b.addEdge(shape.out2, flow.inlet)
        shape
      }

      def <~>[I2, O2](bidi: BidiShape[O, O2, I2, I])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        val flow = b.add(f)
        b.addEdge(flow.outlet, bidi.in1)
        b.addEdge(bidi.out2, flow.inlet)
        bidi
      }

      def <~>[M2](flow: Graph[FlowShape[O, I], M2])(implicit b: Builder[_]): Unit = {
        val shape = b.add(flow)
        val ff = b.add(f)
        b.addEdge(shape.outlet, ff.inlet)
        b.addEdge(ff.outlet, shape.inlet)
      }
    }

    implicit class BidiFlowShapeArrow[I1, O1, I2, O2](val bidi: BidiShape[I1, O1, I2, O2]) extends AnyVal {
      def <~>[I3, O3](other: BidiShape[O1, O3, I3, I2])(implicit b: Builder[_]): BidiShape[O1, O3, I3, I2] = {
        b.addEdge(bidi.out1, other.in1)
        b.addEdge(other.out2, bidi.in2)
        other
      }

      def <~>[I3, O3, M](otherFlow: Graph[BidiShape[O1, O3, I3, I2], M])(implicit b: Builder[_]): BidiShape[O1, O3, I3, I2] = {
        val other = b.add(otherFlow)
        b.addEdge(bidi.out1, other.in1)
        b.addEdge(other.out2, bidi.in2)
        other
      }

      def <~>(flow: FlowShape[O1, I2])(implicit b: Builder[_]): Unit = {
        b.addEdge(bidi.out1, flow.inlet)
        b.addEdge(flow.outlet, bidi.in2)
      }

      def <~>[M](f: Graph[FlowShape[O1, I2], M])(implicit b: Builder[_]): Unit = {
        val flow = b.add(f)
        b.addEdge(bidi.out1, flow.inlet)
        b.addEdge(flow.outlet, bidi.in2)
      }
    }

    import scala.language.implicitConversions

    implicit def port2flow[T](from: Outlet[T])(implicit b: Builder[_]): PortOps[T, Unit] =
      new PortOps(from, b)

    implicit def fanOut2flow[I, O](j: UniformFanOutShape[I, O])(implicit b: Builder[_]): PortOps[O, Unit] =
      new PortOps(findOut(b, j, 0), b)

    implicit def flow2flow[I, O](f: FlowShape[I, O])(implicit b: Builder[_]): PortOps[O, Unit] =
      new PortOps(f.outlet, b)

    implicit class SourceArrow[T](val s: Graph[SourceShape[T], _]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder[_]): Outlet[T] = b.add(s).outlet
    }

    implicit class SourceShapeArrow[T](val s: SourceShape[T]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder[_]): Outlet[T] = s.outlet
    }
  }
}
