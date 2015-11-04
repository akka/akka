/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.Arrays

import akka.event.LoggingAdapter
import akka.stream.stage._
import scala.annotation.tailrec
import scala.collection.immutable
import akka.stream._
import scala.util.control.NonFatal

/**
 * INTERNAL API
 *
 * (See the class for the documentation of the internals)
 */
private[stream] object GraphInterpreter {
  /**
   * Compile time constant, enable it for debug logging to the console.
   */
  final val Debug = false

  final val NoEvent = -1
  final val Boundary = -1

  final val InReady = 1
  final val Pulling = 2
  final val Pushing = 4
  final val OutReady = 8

  final val InClosed = 16
  final val OutClosed = 32
  final val InFailed = 64

  final val PullStartFlip = 3 // 0011
  final val PullEndFlip = 10 // 1010
  final val PushStartFlip = 12 //1100
  final val PushEndFlip = 5 //0101

  /**
   * Marker object that indicates that a port holds no element since it was already grabbed. The port is still pullable,
   * but there is no more element to grab.
   */
  case object Empty
  final case class Failed(ex: Throwable, previousElem: Any)

  abstract class UpstreamBoundaryStageLogic[T] extends GraphStageLogic(inCount = 0, outCount = 1) {
    def out: Outlet[T]
  }

  abstract class DownstreamBoundaryStageLogic[T] extends GraphStageLogic(inCount = 1, outCount = 0) {
    def in: Inlet[T]
  }

  val singleNoAttribute: Array[Attributes] = Array(Attributes.none)

  /**
   * INTERNAL API
   *
   * A GraphAssembly represents a small stream processing graph to be executed by the interpreter. Instances of this
   * class **must not** be mutated after construction.
   *
   * The array ``originalAttributes`` may contain the attribute information of the original atomic module, otherwise
   * it must contain a none (otherwise the enclosing module could not overwrite attributes defined in this array).
   *
   * The arrays [[ins]] and [[outs]] correspond to the notion of a *connection* in the [[GraphInterpreter]]. Each slot
   * *i* contains the input and output port corresponding to connection *i*. Slots where the graph is not closed (i.e.
   * ports are exposed to the external world) are marked with *null* values. For example if an input port *p* is
   * exposed, then outs(p) will contain a *null*.
   *
   * The arrays [[inOwners]] and [[outOwners]] are lookup tables from a connection id (the index of the slot)
   * to a slot in the [[stages]] array, indicating which stage is the owner of the given input or output port.
   * Slots which would correspond to non-existent stages (where the corresponding port is null since it represents
   * the currently unknown external context) contain the value [[GraphInterpreter#Boundary]].
   *
   * The current assumption by the infrastructure is that the layout of these arrays looks like this:
   *
   *            +---------------------------------------+-----------------+
   * inOwners:  | index to stages array                 | Boundary (-1)   |
   *            +----------------+----------------------+-----------------+
   * ins:       | exposed inputs | internal connections | nulls           |
   *            +----------------+----------------------+-----------------+
   * outs:      | nulls          | internal connections | exposed outputs |
   *            +----------------+----------------------+-----------------+
   * outOwners: | Boundary (-1)  | index to stages array                  |
   *            +----------------+----------------------------------------+
   *
   * In addition, it is also assumed by the infrastructure that the order of exposed inputs and outputs in the
   * corresponding segments of these arrays matches the exact same order of the ports in the [[Shape]].
   *
   */
  final class GraphAssembly(val stages: Array[GraphStageWithMaterializedValue[Shape, Any]],
                            val originalAttributes: Array[Attributes],
                            val ins: Array[Inlet[_]],
                            val inOwners: Array[Int],
                            val outs: Array[Outlet[_]],
                            val outOwners: Array[Int]) {
    require(ins.length == inOwners.length && inOwners.length == outs.length && outs.length == outOwners.length)

    def connectionCount: Int = ins.length

    /**
     * Takes an interpreter and returns three arrays required by the interpreter containing the input, output port
     * handlers and the stage logic instances.
     *
     * Returns a tuple of
     *  - lookup table for InHandlers
     *  - lookup table for OutHandlers
     *  - array of the logics
     *  - materialized value
     */
    def materialize(inheritedAttributes: Attributes): (Array[InHandler], Array[OutHandler], Array[GraphStageLogic], Any) = {
      val logics = Array.ofDim[GraphStageLogic](stages.length)
      var finalMat: Any = ()

      var i = 0
      while (i < stages.length) {
        // Port initialization loops, these must come first
        val shape = stages(i).shape

        var idx = 0
        val inletItr = shape.inlets.iterator
        while (inletItr.hasNext) {
          val inlet = inletItr.next()
          require(inlet.id == -1 || inlet.id == idx, s"Inlet $inlet was shared among multiple stages. This is illegal.")
          inlet.id = idx
          idx += 1
        }

        idx = 0
        val outletItr = shape.outlets.iterator
        while (outletItr.hasNext) {
          val outlet = outletItr.next()
          require(outlet.id == -1 || outlet.id == idx, s"Outlet $outlet was shared among multiple stages. This is illegal.")
          outlet.id = idx
          idx += 1
        }

        // FIXME: Support for materialized values in fused islands is not yet figured out!
        val logicAndMat = stages(i).createLogicAndMaterializedValue(inheritedAttributes and originalAttributes(i))
        // FIXME: Current temporary hack to support non-fused stages. If there is one stage that will be under index 0.
        if (i == 0) finalMat = logicAndMat._2

        logics(i) = logicAndMat._1
        i += 1
      }

      val inHandlers = Array.ofDim[InHandler](connectionCount)
      val outHandlers = Array.ofDim[OutHandler](connectionCount)

      i = 0
      while (i < connectionCount) {
        if (ins(i) ne null) {
          val logic = logics(inOwners(i))
          logic.handlers(ins(i).id) match {
            case null         ⇒ throw new IllegalStateException(s"no handler defined in stage $logic for port ${ins(i)}")
            case h: InHandler ⇒ inHandlers(i) = h
          }
          logics(inOwners(i)).portToConn(ins(i).id) = i
        }
        if (outs(i) ne null) {
          val logic = logics(outOwners(i))
          val inCount = logic.inCount
          logic.handlers(outs(i).id + inCount) match {
            case null          ⇒ throw new IllegalStateException(s"no handler defined in stage $logic for port ${outs(i)}")
            case h: OutHandler ⇒ outHandlers(i) = h
          }
          logic.portToConn(outs(i).id + inCount) = i
        }
        i += 1
      }

      (inHandlers, outHandlers, logics, finalMat)
    }

    override def toString: String =
      "GraphAssembly(" +
        stages.mkString("[", ",", "]") + ", " +
        ins.mkString("[", ",", "]") + ", " +
        inOwners.mkString("[", ",", "]") + ", " +
        outs.mkString("[", ",", "]") + ", " +
        outOwners.mkString("[", ",", "]") +
        ")"
  }

  object GraphAssembly {
    /**
     * INTERNAL API
     */
    final def apply(inlets: immutable.Seq[Inlet[_]],
                    outlets: immutable.Seq[Outlet[_]],
                    stages: GraphStageWithMaterializedValue[Shape, _]*): GraphAssembly = {
      // add the contents of an iterator to an array starting at idx
      @tailrec def add[T](i: Iterator[T], a: Array[T], idx: Int): Array[T] =
        if (i.hasNext) {
          a(idx) = i.next()
          add(i, a, idx + 1)
        } else a

      // fill array slots with Boundary
      def markBoundary(owners: Array[Int], from: Int, to: Int): Array[Int] = {
        Arrays.fill(owners, from, to, Boundary)
        owners
      }

      val inletsSize = inlets.size
      val outletsSize = outlets.size
      val connectionCount = inletsSize + outletsSize
      require(connectionCount > 0, s"sum of inlets ({$inletsSize}) & outlets ({$outletsSize}) must be > 0")

      val assembly = new GraphAssembly(
        stages.toArray,
        GraphInterpreter.singleNoAttribute,
        add(inlets.iterator, Array.ofDim(connectionCount), 0),
        markBoundary(Array.ofDim(connectionCount), inletsSize, connectionCount),
        add(outlets.iterator, Array.ofDim(connectionCount), inletsSize),
        markBoundary(Array.ofDim(connectionCount), 0, inletsSize))

      assembly
    }
  }

  /**
   * INTERNAL API
   */
  private val _currentInterpreter = new ThreadLocal[Array[AnyRef]] {
    /*
     * Using an Object-array avoids holding on to the GraphInterpreter class
     * when this accidentally leaks onto threads that are not stopped when this
     * class should be unloaded.
     */
    override def initialValue = new Array(1)
  }

  /**
   * INTERNAL API
   */
  private[stream] def currentInterpreter: GraphInterpreter =
    _currentInterpreter.get()(0).asInstanceOf[GraphInterpreter].nonNull
  // nonNull is just a debug helper to find nulls more timely

  /**
   * INTERNAL API
   */
  private[stream] def currentInterpreterOrNull: GraphInterpreter =
    _currentInterpreter.get()(0).asInstanceOf[GraphInterpreter]

  /**
   * INTERNAL API
   */
  private[stream] def setCurrentInterpreter(gi: GraphInterpreter) =
    _currentInterpreter.get()(0) = gi

}

/**
 * INERNAL API
 *
 * From an external viewpoint, the GraphInterpreter takes an assembly of graph processing stages encoded as a
 * [[GraphInterpreter#GraphAssembly]] object and provides facilities to execute and interact with this assembly.
 * The lifecylce of the Interpreter is roughly the following:
 *  - Boundary logics are attached via [[attachDownstreamBoundary()]] and [[attachUpstreamBoundary()]]
 *  - [[init()]] is called
 *  - [[execute()]] is called whenever there is need for execution, providing an upper limit on the processed events
 *  - [[finish()]] is called before the interpreter is disposed, preferably after [[isCompleted]] returned true, although
 *    in abort cases this is not strictly necessary
 *
 * The [[execute()]] method of the interpreter accepts an upper bound on the events it will process. After this limit
 * is reached or there are no more pending events to be processed, the call returns. It is possible to inspect
 * if there are unprocessed events left via the [[isSuspended]] method. [[isCompleted]] returns true once all stages
 * reported completion inside the interpreter.
 *
 * The internal architecture of the interpreter is based on the usage of arrays and optimized for reducing allocations
 * on the hot paths.
 *
 * One of the basic abstractions inside the interpreter is the notion of *connection*. In the abstract sense a
 * connection represents an output-input port pair (an analogue for a connected RS Publisher-Subscriber pair),
 * while in the practical sense a connection is a number which represents slots in certain arrays.
 * In particular
 *  - portStates contains a bitfield that tracks the states of the ports (output-input) corresponding to this
 *    connection. This bitfield is used to decode the event that is in-flight.
 *  - connectionSlots is a mapping from a connection id to a potential element or exception that accompanies the
 *    event encoded in the portStates bitfield
 *  - inHandlers is a mapping from a connection id to the [[InHandler]] instance that handles the events corresponding
 *    to the input port of the connection
 *  - outHandlers is a mapping from a connection id to the [[OutHandler]] instance that handles the events corresponding
 *    to the output port of the connection
 *
 * On top of these lookup tables there is an eventQueue, represented as a circular buffer of integers. The integers
 * it contains represents connections that have pending events to be processed. The pending event itself is encoded
 * in the portStates bitfield. This implies that there can be only one event in flight for a given connection, which
 * is true in almost all cases, except a complete-after-push or fail-after-push.
 *
 * The layout of the portStates bitfield is the following:
 *
 *             |- state machn.-| Only one bit is hot among these bits
 *  64  32  16 | 8   4   2   1 |
 * +---+---+---|---+---+---+---|
 *   |   |   |   |   |   |   |
 *   |   |   |   |   |   |   |  From the following flags only one is active in any given time. These bits encode
 *   |   |   |   |   |   |   |  state machine states, and they are "moved" around using XOR masks to keep other bits
 *   |   |   |   |   |   |   |  intact.
 *   |   |   |   |   |   |   |
 *   |   |   |   |   |   |   +- InReady:  The input port is ready to be pulled
 *   |   |   |   |   |   +----- Pulling:  A pull is active, but have not arrived yet (queued)
 *   |   |   |   |   +--------- Pushing:  A push is active, but have not arrived yet (queued)
 *   |   |   |   +------------- OutReady: The output port is ready to be pushed
 *   |   |   |
 *   |   |   +----------------- InClosed:  The input port is closed and will not receive any events.
 *   |   |                                 A push might be still in flight which will be then processed first.
 *   |   +--------------------- OutClosed: The output port is closed and will not receive any events.
 *   +------------------------- InFailed:  Always set in conjunction with InClosed. Indicates that the close event
 *                                         is a failure
 *
 * Sending an event is usually the following sequence:
 *  - An action is requested by a stage logic (push, pull, complete, etc.)
 *  - the state machine in portStates is transitioned from a ready state to a pending event
 *  - the id of the affected connection is enqueued
 *
 * Receiving an event is usually the following sequence:
 *  - id of connection to be processed is dequeued
 *  - the type of the event is determined from the bits set on portStates
 *  - the state machine in portStates is transitioned to a ready state
 *  - using the inHandlers/outHandlers table the corresponding callback is called on the stage logic.
 *
 * Because of the FIFO construction of the queue the interpreter is fair, i.e. a pending event is always executed
 * after a bounded number of other events. This property, together with suspendability means that even infinite cycles can
 * be modeled, or even dissolved (if preempted and a "stealing" external event is injected; for example the non-cycle
 * edge of a balance is pulled, dissolving the original cycle).
 */
private[stream] final class GraphInterpreter(
  private val assembly: GraphInterpreter.GraphAssembly,
  val materializer: Materializer,
  val log: LoggingAdapter,
  val inHandlers: Array[InHandler], // Lookup table for the InHandler of a connection
  val outHandlers: Array[OutHandler], // Lookup table for the outHandler of the connection
  val logics: Array[GraphStageLogic], // Array of stage logics
  val onAsyncInput: (GraphStageLogic, Any, (Any) ⇒ Unit) ⇒ Unit) {
  import GraphInterpreter._

  // Maintains additional information for events, basically elements in-flight, or failure.
  // Other events are encoded in the portStates bitfield.
  val connectionSlots = Array.fill[Any](assembly.connectionCount)(Empty)

  // Bitfield encoding pending events and various states for efficient querying and updates. See the documentation
  // of the class for a full description.
  val portStates = Array.fill[Int](assembly.connectionCount)(InReady)

  /**
   * INTERNAL API
   */
  private[stream] var activeStage: GraphStageLogic = _

  // The number of currently running stages. Once this counter reaches zero, the interpreter is considered to be
  // completed
  private[this] var runningStages = assembly.stages.length

  // Counts how many active connections a stage has. Once it reaches zero, the stage is automatically stopped.
  private[this] val shutdownCounter = Array.tabulate(assembly.stages.length) { i ⇒
    val shape = assembly.stages(i).shape
    shape.inlets.size + shape.outlets.size
  }

  // An event queue implemented as a circular buffer
  // FIXME: This calculates the maximum size ever needed, but most assemblies can run on a smaller queue
  private[this] val eventQueue = Array.ofDim[Int](1 << (32 - Integer.numberOfLeadingZeros(assembly.connectionCount - 1)))
  private[this] val mask = eventQueue.length - 1
  private[this] var queueHead: Int = 0
  private[this] var queueTail: Int = 0

  private def queueStatus: String = {
    val contents = (queueHead until queueTail).map(idx ⇒ {
      val conn = eventQueue(idx & mask)
      (conn, portStates(conn), connectionSlots(conn))
    })
    s"(${eventQueue.length}, $queueHead, $queueTail)(${contents.mkString(", ")})"
  }
  private[this] var _Name: String = _
  def Name: String =
    if (_Name eq null) {
      _Name = f"${System.identityHashCode(this)}%08X"
      _Name
    } else _Name

  /**
   * INTERNAL API
   */
  private[stream] def nonNull: GraphInterpreter = this

  /**
   * Assign the boundary logic to a given connection. This will serve as the interface to the external world
   * (outside the interpreter) to process and inject events.
   */
  def attachUpstreamBoundary(connection: Int, logic: UpstreamBoundaryStageLogic[_]): Unit = {
    logic.portToConn(logic.out.id + logic.inCount) = connection
    logic.interpreter = this
    outHandlers(connection) = logic.handlers(0).asInstanceOf[OutHandler]
  }

  /**
   * Assign the boundary logic to a given connection. This will serve as the interface to the external world
   * (outside the interpreter) to process and inject events.
   */
  def attachDownstreamBoundary(connection: Int, logic: DownstreamBoundaryStageLogic[_]): Unit = {
    logic.portToConn(logic.in.id) = connection
    logic.interpreter = this
    inHandlers(connection) = logic.handlers(0).asInstanceOf[InHandler]
  }

  /**
   * Dynamic handler changes are communicated from a GraphStageLogic by this method.
   */
  def setHandler(connection: Int, handler: InHandler): Unit = {
    if (Debug) println(s"$Name SETHANDLER ${inOwnerName(connection)} (in) $handler")
    inHandlers(connection) = handler
  }

  /**
   * Dynamic handler changes are communicated from a GraphStageLogic by this method.
   */
  def setHandler(connection: Int, handler: OutHandler): Unit = {
    if (Debug) println(s"$Name SETHANDLER ${outOwnerName(connection)} (out) $handler")
    outHandlers(connection) = handler
  }

  /**
   * Returns true if there are pending unprocessed events in the event queue.
   */
  def isSuspended: Boolean = queueHead != queueTail

  /**
   * Returns true if there are no more running stages and pending events.
   */
  def isCompleted: Boolean = runningStages == 0 && !isSuspended

  /**
   * Initializes the states of all the stage logics by calling preStart()
   */
  def init(): Unit = {
    var i = 0
    while (i < logics.length) {
      val logic = logics(i)
      logic.stageId = i
      logic.interpreter = this
      try {
        logic.beforePreStart()
        logic.preStart()
      } catch {
        case NonFatal(e) ⇒ logic.failStage(e)
      }
      afterStageHasRun(logic)
      i += 1
    }
  }

  /**
   * Finalizes the state of all stages by calling postStop() (if necessary).
   */
  def finish(): Unit = {
    var i = 0
    while (i < logics.length) {
      val logic = logics(i)
      if (!isStageCompleted(logic)) finalizeStage(logic)
      i += 1
    }
  }

  // Debug name for a connections input part
  private def inOwnerName(connection: Int): String =
    assembly.inOwners(connection) match {
      case Boundary ⇒ "DownstreamBoundary"
      case owner    ⇒ assembly.stages(owner).toString
    }

  // Debug name for a connections ouput part
  private def outOwnerName(connection: Int): String =
    assembly.outOwners(connection) match {
      case Boundary ⇒ "UpstreamBoundary"
      case owner    ⇒ assembly.stages(owner).toString
    }

  // Debug name for a connections input part
  private def inLogicName(connection: Int): String =
    assembly.inOwners(connection) match {
      case Boundary ⇒ "DownstreamBoundary"
      case owner    ⇒ logics(owner).toString
    }

  // Debug name for a connections ouput part
  private def outLogicName(connection: Int): String =
    assembly.outOwners(connection) match {
      case Boundary ⇒ "UpstreamBoundary"
      case owner    ⇒ logics(owner).toString
    }

  /**
   * Executes pending events until the given limit is met. If there were remaining events, isSuspended will return
   * true.
   */
  def execute(eventLimit: Int): Unit = {
    if (Debug) println(s"$Name ---------------- EXECUTE (running=$runningStages, shutdown=${shutdownCounter.mkString(",")})")
    val previousInterpreter = currentInterpreterOrNull
    setCurrentInterpreter(this)
    try {
      var eventsRemaining = eventLimit
      while (eventsRemaining > 0 && queueTail != queueHead) {
        val connection = dequeue()
        try processEvent(connection)
        catch {
          case NonFatal(e) ⇒
            if (activeStage == null) throw e
            else activeStage.failStage(e)
        }
        afterStageHasRun(activeStage)
        eventsRemaining -= 1
      }
    } finally {
      setCurrentInterpreter(previousInterpreter)
    }
    if (Debug) println(s"$Name ---------------- $queueStatus (running=$runningStages, shutdown=${shutdownCounter.mkString(",")})")
    // TODO: deadlock detection
  }

  // Decodes and processes a single event for the given connection
  private def processEvent(connection: Int): Unit = {
    def safeLogics(id: Int) =
      if (id == Boundary) null
      else logics(id)

    def processElement(): Unit = {
      if (Debug) println(s"$Name PUSH ${outOwnerName(connection)} -> ${inOwnerName(connection)}, ${connectionSlots(connection)} (${inHandlers(connection)}) [${inLogicName(connection)}]")
      activeStage = safeLogics(assembly.inOwners(connection))
      portStates(connection) ^= PushEndFlip
      inHandlers(connection).onPush()
    }

    // this must be the state after returning without delivering any signals, to avoid double-finalization of some unlucky stage
    // (this can happen if a stage completes voluntarily while connection close events are still queued)
    activeStage = null
    val code = portStates(connection)

    // Manual fast decoding, fast paths are PUSH and PULL
    //   PUSH
    if ((code & (Pushing | InClosed | OutClosed)) == Pushing) {
      processElement()

      // PULL
    } else if ((code & (Pulling | OutClosed | InClosed)) == Pulling) {
      if (Debug) println(s"$Name PULL ${inOwnerName(connection)} -> ${outOwnerName(connection)} (${outHandlers(connection)}) [${outLogicName(connection)}]")
      portStates(connection) ^= PullEndFlip
      activeStage = safeLogics(assembly.outOwners(connection))
      outHandlers(connection).onPull()

      // CANCEL
    } else if ((code & (OutClosed | InClosed)) == InClosed) {
      val stageId = assembly.outOwners(connection)
      activeStage = safeLogics(stageId)
      if (Debug) println(s"$Name CANCEL ${inOwnerName(connection)} -> ${outOwnerName(connection)} (${outHandlers(connection)}) [${outLogicName(connection)}]")
      portStates(connection) |= OutClosed
      completeConnection(stageId)
      outHandlers(connection).onDownstreamFinish()
    } else if ((code & (OutClosed | InClosed)) == OutClosed) {
      // COMPLETIONS

      if ((code & Pushing) == 0) {
        // Normal completion (no push pending)
        if (Debug) println(s"$Name COMPLETE ${outOwnerName(connection)} -> ${inOwnerName(connection)} (${inHandlers(connection)}) [${inLogicName(connection)}]")
        portStates(connection) |= InClosed
        val stageId = assembly.inOwners(connection)
        activeStage = safeLogics(stageId)
        completeConnection(stageId)
        if ((portStates(connection) & InFailed) == 0) inHandlers(connection).onUpstreamFinish()
        else inHandlers(connection).onUpstreamFailure(connectionSlots(connection).asInstanceOf[Failed].ex)
      } else {
        // Push is pending, first process push, then re-enqueue closing event
        processElement()
        enqueue(connection)
      }

    }
  }

  private def dequeue(): Int = {
    if (queueHead == queueTail) NoEvent
    else {
      val idx = queueHead & mask
      val elem = eventQueue(idx)
      eventQueue(idx) = NoEvent
      queueHead += 1
      elem
    }
  }

  private def enqueue(connection: Int): Unit = {
    if (Debug) if (queueTail - queueHead > mask) new Exception(s"$Name internal queue full ($queueStatus) + $connection").printStackTrace()
    eventQueue(queueTail & mask) = connection
    queueTail += 1
  }

  def afterStageHasRun(logic: GraphStageLogic): Unit =
    if (isStageCompleted(logic)) {
      runningStages -= 1
      finalizeStage(logic)
    }

  // Returns true if the given stage is alredy completed
  def isStageCompleted(stage: GraphStageLogic): Boolean = stage != null && shutdownCounter(stage.stageId) == 0

  // Register that a connection in which the given stage participated has been completed and therefore the stage
  // itself might stop, too.
  private def completeConnection(stageId: Int): Unit = {
    if (stageId != Boundary) {
      val activeConnections = shutdownCounter(stageId)
      if (activeConnections > 0) shutdownCounter(stageId) = activeConnections - 1
    }
  }

  private def finalizeStage(logic: GraphStageLogic): Unit = {
    try {
      logic.postStop()
      logic.afterPostStop()
    } catch {
      case NonFatal(e) ⇒
        log.error(s"Error during postStop in [${assembly.stages(logic.stageId)}]", e)
    }
  }

  private[stream] def push(connection: Int, elem: Any): Unit = {
    if ((portStates(connection) & InClosed) == 0) {
      portStates(connection) ^= PushStartFlip
      connectionSlots(connection) = elem
      enqueue(connection)
    }
  }

  private[stream] def pull(connection: Int): Unit = {
    val currentState = portStates(connection)
    if ((currentState & OutClosed) == 0) {
      portStates(connection) = currentState ^ PullStartFlip
      enqueue(connection)
    }
  }

  private[stream] def complete(connection: Int): Unit = {
    val currentState = portStates(connection)
    if (Debug) println(s"$Name   complete($connection) [$currentState]")
    portStates(connection) = currentState | OutClosed
    if ((currentState & (InClosed | Pushing | Pulling)) == 0) enqueue(connection)
    if ((currentState & OutClosed) == 0) completeConnection(assembly.outOwners(connection))
  }

  private[stream] def fail(connection: Int, ex: Throwable): Unit = {
    val currentState = portStates(connection)
    if (Debug) println(s"$Name   fail($connection, $ex) [$currentState]")
    portStates(connection) = currentState | (OutClosed | InFailed)
    if ((currentState & InClosed) == 0) {
      connectionSlots(connection) = Failed(ex, connectionSlots(connection))
      if ((currentState & (Pulling | Pushing)) == 0) enqueue(connection)
    }
    if ((currentState & OutClosed) == 0) completeConnection(assembly.outOwners(connection))
  }

  private[stream] def cancel(connection: Int): Unit = {
    val currentState = portStates(connection)
    if (Debug) println(s"$Name   cancel($connection) [$currentState]")
    portStates(connection) = currentState | InClosed
    if ((currentState & OutClosed) == 0) {
      connectionSlots(connection) = Empty
      if ((currentState & (Pulling | Pushing)) == 0) enqueue(connection)
    }
    if ((currentState & InClosed) == 0) completeConnection(assembly.inOwners(connection))
  }

}
