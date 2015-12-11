/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Stages.{ StageModule, SymbolicStage }
import akka.stream.impl._
import akka.stream.impl.StreamLayout._
import akka.stream._
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
final class Merge[T] private (val inputPorts: Int, val eagerClose: Boolean) extends GraphStage[UniformFanInShape[T, T]] {
  require(inputPorts > 1, "A Merge must have more than 1 input port")
  val in: immutable.IndexedSeq[Inlet[T]] = Vector.tabulate(inputPorts)(i ⇒ Inlet[T]("Merge.in" + i))
  val out: Outlet[T] = Outlet[T]("Merge.out")
  override val shape: UniformFanInShape[T, T] = UniformFanInShape(out, in: _*)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
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
 * '''Completes when''' all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
 *
 * '''Cancels when''' downstream cancels
 *
 * A `Broadcast` has one `in` port and 2 or more `out` ports.
 */
final class MergePreferred[T] private (val secondaryPorts: Int, val eagerClose: Boolean) extends GraphStage[MergePreferred.MergePreferredShape[T]] {
  require(secondaryPorts >= 1, "A MergePreferred must have more than 0 secondary input ports")
  override val shape: MergePreferred.MergePreferredShape[T] =
    new MergePreferred.MergePreferredShape(secondaryPorts, "MergePreferred")

  def in(id: Int): Inlet[T] = shape.in(id)
  def out: Outlet[T] = shape.out
  def preferred: Inlet[T] = shape.preferred

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var openInputs = secondaryPorts + 1
    def onComplete(): Unit = {
      openInputs -= 1
      if (eagerClose || openInputs == 0) completeStage()
    }

    setHandler(out, new OutHandler {
      private var first = true
      override def onPull(): Unit = {
        if (first) {
          first = false
          tryPull(preferred)
          shape.inSeq.foreach(tryPull)
        }
      }
    })

    val pullMe = Array.tabulate(secondaryPorts)(i ⇒ {
      val port = in(i)
      () ⇒ tryPull(port)
    })

    /*
     * This determines the unfairness of the merge:
     * - at 1 the preferred will grab 40% of the bandwidth against three equally fast secondaries
     * - at 2 the preferred will grab almost all bandwidth against three equally fast secondaries
     * (measured with eventLimit=1 in the GraphInterpreter, so may not be accurate)
     */
    val maxEmitting = 2
    var preferredEmitting = 0

    setHandler(preferred, new InHandler {
      override def onUpstreamFinish(): Unit = onComplete()
      override def onPush(): Unit =
        if (preferredEmitting == maxEmitting) () // blocked
        else emitPreferred()

      def emitPreferred(): Unit = {
        preferredEmitting += 1
        emit(out, grab(preferred), emitted)
        tryPull(preferred)
      }

      val emitted = () ⇒ {
        preferredEmitting -= 1
        if (isAvailable(preferred)) emitPreferred()
        else if (preferredEmitting == 0) emitSecondary()
      }

      def emitSecondary(): Unit = {
        var i = 0
        while (i < secondaryPorts) {
          val port = in(i)
          if (isAvailable(port)) emit(out, grab(port), pullMe(i))
          i += 1
        }
      }
    })

    var i = 0
    while (i < secondaryPorts) {
      val port = in(i)
      val pullPort = pullMe(i)
      setHandler(port, new InHandler {
        override def onPush(): Unit = {
          if (preferredEmitting > 0) () // blocked
          else {
            emit(out, grab(port), pullPort)
          }
        }
        override def onUpstreamFinish(): Unit = onComplete()
      })
      i += 1
    }

  }
}

object Interleave {
  /**
   * Create a new `Interleave` with the specified number of input ports and given size of elements
   * to take from each input.
   *
   * @param inputPorts number of input ports
   * @param segmentSize number of elements to send downstream before switching to next input port
   * @param eagerClose if true, interleave completes upstream if any of its upstream completes.
   */
  def apply[T](inputPorts: Int, segmentSize: Int, eagerClose: Boolean = false): Interleave[T] =
    new Interleave(inputPorts, segmentSize, eagerClose)
}

/**
 * Interleave represents deterministic merge which takes N elements per input stream,
 * in-order of inputs, emits them downstream and then cycles/"wraps-around" the inputs.
 *
 * '''Emits when''' element is available from current input (depending on phase)
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
 *
 * '''Cancels when''' downstream cancels
 *
 */
final class Interleave[T] private (val inputPorts: Int, val segmentSize: Int, val eagerClose: Boolean) extends GraphStage[UniformFanInShape[T, T]] {
  require(inputPorts > 1, "input ports must be > 1")
  require(segmentSize > 0, "segmentSize must be > 0")

  val in: immutable.IndexedSeq[Inlet[T]] = Vector.tabulate(inputPorts)(i ⇒ Inlet[T]("Interleave.in" + i))
  val out: Outlet[T] = Outlet[T]("Interleave.out")
  override val shape: UniformFanInShape[T, T] = UniformFanInShape(out, in: _*)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var counter = 0
    private var currentUpstreamIndex = 0
    private var runningUpstreams = inputPorts
    private def upstreamsClosed = runningUpstreams == 0
    private def currentUpstream = in(currentUpstreamIndex)

    private def switchToNextInput(): Unit = {
      @tailrec
      def nextInletIndex(index: Int): Int = {
        val reduced = (index + 1) % inputPorts
        if (!isClosed(in(reduced))) reduced else nextInletIndex(index + 1)
      }
      counter = 0
      currentUpstreamIndex = nextInletIndex(currentUpstreamIndex)
    }

    in.foreach { i ⇒
      setHandler(i, new InHandler {
        override def onPush(): Unit = {
          push(out, grab(i))
          counter += 1
          if (counter == segmentSize) switchToNextInput()
        }

        override def onUpstreamFinish() = {
          if (eagerClose) {
            in.foreach(cancel)
            completeStage()
          } else {
            runningUpstreams -= 1
            if (!upstreamsClosed) {
              if (i == currentUpstream) {
                switchToNextInput()
                if (isAvailable(out)) pull(currentUpstream)
              }
            } else completeStage()
          }
        }
      })
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = if (!hasBeenPulled(currentUpstream)) pull(currentUpstream)
    })
  }

  override def toString = "Interleave"
}

object Broadcast {
  /**
   * Create a new `Broadcast` with the specified number of output ports.
   *
   * @param outputPorts number of output ports
   * @param eagerCancel if true, broadcast cancels upstream if any of its downstreams cancel.
   */
  def apply[T](outputPorts: Int, eagerCancel: Boolean = false): Broadcast[T] =
    new Broadcast(outputPorts, eagerCancel)
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
final class Broadcast[T](private val outputPorts: Int, eagerCancel: Boolean) extends GraphStage[UniformFanOutShape[T, T]] {
  require(outputPorts > 1, "A Broadcast must have more than 1 output ports")
  val in: Inlet[T] = Inlet[T]("Broadast.in")
  val out: immutable.IndexedSeq[Outlet[T]] = Vector.tabulate(outputPorts)(i ⇒ Outlet[T]("Broadcast.out" + i))
  override val shape: UniformFanOutShape[T, T] = UniformFanOutShape(in, out: _*)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
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
  def apply[T](outputPorts: Int, waitForAllDownstreams: Boolean = false): Balance[T] =
    new Balance(outputPorts, waitForAllDownstreams)
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
final class Balance[T](val outputPorts: Int, waitForAllDownstreams: Boolean) extends GraphStage[UniformFanOutShape[T, T]] {
  require(outputPorts > 1, "A Balance must have more than 1 output ports")
  val in: Inlet[T] = Inlet[T]("Balance.in")
  val out: immutable.IndexedSeq[Outlet[T]] = Vector.tabulate(outputPorts)(i ⇒ Outlet[T]("Balance.out" + i))
  override val shape: UniformFanOutShape[T, T] = UniformFanOutShape[T, T](in, out: _*)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
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
final class Zip[A, B] extends ZipWith2[A, B, (A, B)](Pair.apply) {
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
  /**
   * Create a new `Unzip`.
   */
  def apply[A, B](): Unzip[A, B] = new Unzip()
}

/**
 * Combine the elements of multiple streams into a stream of the combined elements.
 */
final class Unzip[A, B]() extends UnzipWith2[(A, B), A, B](ConstantFun.scalaIdentityFunction) {
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
  def apply[T](inputPorts: Int = 2): Concat[T] = new Concat(inputPorts)
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
final class Concat[T](inputPorts: Int) extends GraphStage[UniformFanInShape[T, T]] {
  require(inputPorts > 1, "A Concat must have more than 1 input ports")
  val in: immutable.IndexedSeq[Inlet[T]] = Vector.tabulate(inputPorts)(i ⇒ Inlet[T]("Concat.in" + i))
  val out: Outlet[T] = Outlet[T]("Concat.out")
  override val shape: UniformFanInShape[T, T] = UniformFanInShape(out, in: _*)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
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
              while (activeStream < inputPorts && isClosed(in(activeStream))) activeStream += 1
              if (activeStream == inputPorts) completeStage()
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

object GraphDSL extends GraphApply {

  class Builder[+M] private[stream] () {
    private var moduleInProgress: Module = EmptyModule

    /**
     * INTERNAL API
     */
    private[GraphDSL] def addEdge[T, U >: T](from: Outlet[T], to: Inlet[U]): Unit =
      moduleInProgress = moduleInProgress.wire(from, to)

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

    private[stream] def deprecatedAndThen(port: OutPort, op: StageModule): Unit = {
      moduleInProgress =
        moduleInProgress
          .compose(op)
          .wire(port, op.inPort)
    }

    private[stream] def module: Module = moduleInProgress

    /** Converts this Scala DSL element to it's Java DSL counterpart. */
    def asJava: javadsl.GraphDSL.Builder[M] = new javadsl.GraphDSL.Builder()(this)
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

    sealed trait CombinerBase[+T] extends Any {
      def importAndGetPort(b: Builder[_]): Outlet[T @uncheckedVariance]

      def ~>[U >: T](to: Inlet[U])(implicit b: Builder[_]): Unit =
        b.addEdge(importAndGetPort(b), to)

      def ~>[Out](via: Graph[FlowShape[T, Out], Any])(implicit b: Builder[_]): PortOps[Out] = {
        val s = b.add(via)
        b.addEdge(importAndGetPort(b), s.inlet)
        s.outlet
      }

      def ~>[Out](junction: UniformFanInShape[T, Out])(implicit b: Builder[_]): PortOps[Out] = {
        def bind(n: Int): Unit = {
          if (n == junction.inSeq.length)
            throw new IllegalArgumentException(s"no more inlets free on $junction")
          else if (b.module.upstreams.contains(junction.in(n))) bind(n + 1)
          else b.addEdge(importAndGetPort(b), junction.in(n))
        }
        bind(0)
        junction.out
      }

      def ~>[Out](junction: UniformFanOutShape[T, Out])(implicit b: Builder[_]): PortOps[Out] = {
        b.addEdge(importAndGetPort(b), junction.in)
        try findOut(b, junction, 0)
        catch {
          case e: IllegalArgumentException ⇒ new DisabledPortOps(e.getMessage)
        }
      }

      def ~>[Out](flow: FlowShape[T, Out])(implicit b: Builder[_]): PortOps[Out] = {
        b.addEdge(importAndGetPort(b), flow.inlet)
        flow.outlet
      }

      def ~>(to: Graph[SinkShape[T], _])(implicit b: Builder[_]): Unit =
        b.addEdge(importAndGetPort(b), b.add(to).inlet)

      def ~>(to: SinkShape[T])(implicit b: Builder[_]): Unit =
        b.addEdge(importAndGetPort(b), to.inlet)
    }

    sealed trait ReverseCombinerBase[T] extends Any {
      def importAndGetPortReverse(b: Builder[_]): Inlet[T]

      def <~[U <: T](from: Outlet[U])(implicit b: Builder[_]): Unit =
        b.addEdge(from, importAndGetPortReverse(b))

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

      def <~(from: Graph[SourceShape[T], _])(implicit b: Builder[_]): Unit =
        b.addEdge(b.add(from).outlet, importAndGetPortReverse(b))

      def <~(from: SourceShape[T])(implicit b: Builder[_]): Unit =
        b.addEdge(from.outlet, importAndGetPortReverse(b))
    }

    // Although Mat is always Unit, it cannot be removed as a type parameter, otherwise the "override type"
    // won't work below
    trait PortOps[+Out] extends FlowOps[Out, Unit] with CombinerBase[Out] {
      override type Repr[+O] = PortOps[O]
      override type Closed = Unit
      def outlet: Outlet[Out @uncheckedVariance]
    }

    private class PortOpsImpl[+Out](override val outlet: Outlet[Out @uncheckedVariance], b: Builder[_])
      extends PortOps[Out] {

      override def withAttributes(attr: Attributes): Repr[Out] =
        throw new UnsupportedOperationException("Cannot set attributes on chained ops from a junction output port")

      override def importAndGetPort(b: Builder[_]): Outlet[Out @uncheckedVariance] = outlet

      override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T] =
        super.~>(flow)(b)

      override private[scaladsl] def deprecatedAndThen[U](op: StageModule): Repr[U] = {
        b.deprecatedAndThen(outlet, op)
        new PortOpsImpl(op.shape.outlet.asInstanceOf[Outlet[U]], b)
      }

      def to[Mat2](sink: Graph[SinkShape[Out], Mat2]): Closed =
        super.~>(sink)(b)

    }

    private class DisabledPortOps[Out, Mat](msg: String) extends PortOpsImpl[Out](null, null) {
      override def importAndGetPort(b: Builder[_]): Outlet[Out] = throw new IllegalArgumentException(msg)

      override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T] =
        throw new IllegalArgumentException(msg)
    }

    implicit class ReversePortOps[In](val inlet: Inlet[In]) extends ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = inlet
    }

    final class DisabledReversePortOps[In](msg: String) extends ReversePortOps[In](null) {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = throw new IllegalArgumentException(msg)
    }

    implicit final class FanInOps[In, Out](val j: UniformFanInShape[In, Out]) extends AnyVal with CombinerBase[Out] with ReverseCombinerBase[In] {
      override def importAndGetPort(b: Builder[_]): Outlet[Out] = j.out
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = findIn(b, j, 0)
    }

    implicit final class FanOutOps[In, Out](val j: UniformFanOutShape[In, Out]) extends AnyVal with ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = j.in
    }

    implicit final class SinkArrow[T](val s: Graph[SinkShape[T], _]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[T] = b.add(s).inlet
    }

    implicit final class SinkShapeArrow[T](val s: SinkShape[T]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[T] = s.inlet
    }

    implicit final class FlowShapeArrow[I, O](val f: FlowShape[I, O]) extends AnyVal with ReverseCombinerBase[I] {
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

    implicit final class FlowArrow[I, O, M](val f: Graph[FlowShape[I, O], M]) extends AnyVal {
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

    implicit final class BidiFlowShapeArrow[I1, O1, I2, O2](val bidi: BidiShape[I1, O1, I2, O2]) extends AnyVal {
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

    implicit def port2flow[T](from: Outlet[T])(implicit b: Builder[_]): PortOps[T] =
      new PortOpsImpl(from, b)

    implicit def fanOut2flow[I, O](j: UniformFanOutShape[I, O])(implicit b: Builder[_]): PortOps[O] =
      new PortOpsImpl(findOut(b, j, 0), b)

    implicit def flow2flow[I, O](f: FlowShape[I, O])(implicit b: Builder[_]): PortOps[O] =
      new PortOpsImpl(f.outlet, b)

    implicit final class SourceArrow[T](val s: Graph[SourceShape[T], _]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder[_]): Outlet[T] = b.add(s).outlet
    }

    implicit final class SourceShapeArrow[T](val s: SourceShape[T]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder[_]): Outlet[T] = s.outlet
    }
  }
}
