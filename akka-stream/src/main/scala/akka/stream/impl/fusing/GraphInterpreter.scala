/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.stage._
import akka.stream.{ Materializer, Shape, Inlet, Outlet }

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

  /**
   * Marker object that indicates that a port holds no element since it was already grabbed. The port is still pullable,
   * but there is no more element to grab.
   */
  case object Empty

  sealed trait ConnectionState
  case object Pulled extends ConnectionState

  sealed trait HasElementState

  sealed trait CompletingState extends ConnectionState
  final case class CompletedHasElement(element: Any) extends CompletingState with HasElementState
  final case class PushCompleted(element: Any) extends CompletingState with HasElementState
  case object Completed extends CompletingState
  case object Cancelled extends CompletingState
  final case class Failed(ex: Throwable) extends CompletingState

  val NoEvent = -1
  val Boundary = -1

  sealed trait PortState
  case object InFlight extends PortState
  case object Available extends PortState
  case object Closed extends PortState

  abstract class UpstreamBoundaryStageLogic[T] extends GraphStageLogic {
    def out: Outlet[T]
  }

  abstract class DownstreamBoundaryStageLogic[T] extends GraphStageLogic {
    def in: Inlet[T]
  }

  /**
   * INTERNAL API
   *
   * A GraphAssembly represents a small stream processing graph to be executed by the interpreter. Instances of this
   * class **must not** be mutated after construction.
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
  final case class GraphAssembly(stages: Array[GraphStageWithMaterializedValue[_, _]],
                                 ins: Array[Inlet[_]],
                                 inOwners: Array[Int],
                                 outs: Array[Outlet[_]],
                                 outOwners: Array[Int]) {

    val connectionCount: Int = ins.length

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
    def materialize(): (Array[InHandler], Array[OutHandler], Array[GraphStageLogic], Any) = {
      val logics = Array.ofDim[GraphStageLogic](stages.length)
      var finalMat: Any = ()

      for (i ← stages.indices) {
        // FIXME: Support for materialized values in fused islands is not yet figured out!
        val (logic, mat) = stages(i).createLogicAndMaterializedValue
        // FIXME: Current temporary hack to support non-fused stages. If there is one stage that will be under index 0.
        if (i == 0) finalMat = mat

        logics(i) = logic
      }

      val inHandlers = Array.ofDim[InHandler](connectionCount)
      val outHandlers = Array.ofDim[OutHandler](connectionCount)

      for (i ← 0 until connectionCount) {
        if (ins(i) ne null) {
          inHandlers(i) = logics(inOwners(i)).inHandlers(ins(i))
          logics(inOwners(i)).inToConn += ins(i) -> i
        }
        if (outs(i) ne null) {
          outHandlers(i) = logics(outOwners(i)).outHandlers(outs(i))
          logics(outOwners(i)).outToConn += outs(i) -> i
        }
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
 *  - connectionStates is a mapping from a connection id to a current (or future) state of the connection
 *  - inStates is a mapping from a connection to a [[akka.stream.impl.fusing.GraphInterpreter.PortState]]
 *    that indicates whether the input corresponding
 *    to the connection is currently pullable or completed
 *  - outStates is a mapping from a connection to a [[akka.stream.impl.fusing.GraphInterpreter.PortState]]
 *    that indicates whether the input corresponding to the connection is currently pushable or completed
 *  - inHandlers is a mapping from a connection id to the [[InHandler]] instance that handles the events corresponding
 *    to the input port of the connection
 *  - outHandlers is a mapping from a connection id to the [[OutHandler]] instance that handles the events corresponding
 *    to the output port of the connection
 *
 * On top of these lookup tables there is an eventQueue, represented as a circular buffer of integers. The integers
 * it contains represents connections that have pending events to be processed. The pending event itself is encoded
 * in the connectionStates table. This implies that there can be only one event in flight for a given connection, which
 * is true in almost all cases, except a complete-after-push which is therefore handled with a special event
 * [[GraphInterpreter#PushCompleted]].
 *
 * Sending an event is usually the following sequence:
 *  - An action is requested by a stage logic (push, pull, complete, etc.)
 *  - the availability of the port is set on the sender side to Limbo (inStates or outStates)
 *  - the scheduled event is put in the slot of the connection in the connectionStates table
 *  - the id of the affected connection is enqueued
 *
 * Receiving an event is usually the following sequence:
 *  - id of connection to be processed is dequeued
 *  - the type of the event is determined by the object in the corresponding connectionStates slot
 *  - the availability of the port is set on the receiver side to be Available (inStates or outStates)
 *  - using the inHandlers/outHandlers table the corresponding callback is called on the stage logic.
 *
 * Because of the FIFO construction of the queue the interpreter is fair, i.e. a pending event is always executed
 * after a bounded number of other events. This property, together with suspendability means that even infinite cycles can
 * be modeled, or even dissolved (if preempted and a "stealing" external even is injected; for example the non-cycle
 * edge of a balance is pulled, dissolving the original cycle).
 */
private[stream] final class GraphInterpreter(
  private val assembly: GraphInterpreter.GraphAssembly,
  val materializer: Materializer,
  val inHandlers: Array[InHandler], // Lookup table for the InHandler of a connection
  val outHandlers: Array[OutHandler], // Lookup table for the outHandler of the connection
  val logics: Array[GraphStageLogic], // Array of stage logics
  val onAsyncInput: (GraphStageLogic, Any, (Any) ⇒ Unit) ⇒ Unit) {
  import GraphInterpreter._

  // Maintains the next event (and state) of the connection.
  // Technically the connection cannot be considered being in the state that is encoded here before the enqueued
  // connection event has been processed. The inStates and outStates arrays usually protect access to this
  // field while it is in transient state.
  val connectionStates = Array.fill[Any](assembly.connectionCount)(Empty)

  // Indicates whether the input port is pullable. After pulling it becomes false
  // Be aware that when inAvailable goes to false outAvailable does not become true immediately, only after
  // the corresponding event in the queue has been processed
  val inStates = Array.fill[PortState](assembly.connectionCount)(Available)

  // Indicates whether the output port is pushable. After pushing it becomes false
  // Be aware that when inAvailable goes to false outAvailable does not become true immediately, only after
  // the corresponding event in the queue has been processed
  val outStates = Array.fill[PortState](assembly.connectionCount)(InFlight)

  // The number of currently running stages. Once this counter reaches zero, the interpreter is considered to be
  // completed
  private var runningStages = assembly.stages.length

  // Counts how many active connections a stage has. Once it reaches zero, the stage is automatically stopped.
  private val shutdownCounter = Array.tabulate(assembly.stages.length) { i ⇒
    val shape = assembly.stages(i).shape.asInstanceOf[Shape]
    shape.inlets.size + shape.outlets.size
  }

  // An event queue implemented as a circular buffer
  private val eventQueue = Array.ofDim[Int](256)
  private val mask = eventQueue.length - 1
  private var queueHead: Int = 0
  private var queueTail: Int = 0

  /**
   * Assign the boundary logic to a given connection. This will serve as the interface to the external world
   * (outside the interpreter) to process and inject events.
   */
  def attachUpstreamBoundary(connection: Int, logic: UpstreamBoundaryStageLogic[_]): Unit = {
    logic.outToConn += logic.out -> connection
    logic.interpreter = this
    outHandlers(connection) = logic.outHandlers.head._2
  }

  /**
   * Assign the boundary logic to a given connection. This will serve as the interface to the external world
   * (outside the interpreter) to process and inject events.
   */
  def attachDownstreamBoundary(connection: Int, logic: DownstreamBoundaryStageLogic[_]): Unit = {
    logic.inToConn += logic.in -> connection
    logic.interpreter = this
    inHandlers(connection) = logic.inHandlers.head._2
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
      logics(i).stageId = i
      logics(i).interpreter = this
      logics(i).beforePreStart()
      logics(i).preStart()
      i += 1
    }
  }

  /**
   * Finalizes the state of all stages by calling postStop() (if necessary).
   */
  def finish(): Unit = {
    var i = 0
    while (i < logics.length) {
      if (!isStageCompleted(i)) {
        logics(i).postStop()
        logics(i).afterPostStop()
      }
      i += 1
    }
  }

  // Debug name for a connections input part
  private def inOwnerName(connection: Int): String =
    if (assembly.inOwners(connection) == Boundary) "DownstreamBoundary"
    else assembly.stages(assembly.inOwners(connection)).toString

  // Debug name for a connections ouput part
  private def outOwnerName(connection: Int): String =
    if (assembly.outOwners(connection) == Boundary) "UpstreamBoundary"
    else assembly.stages(assembly.outOwners(connection)).toString

  /**
   * Executes pending events until the given limit is met. If there were remaining events, isSuspended will return
   * true.
   */
  def execute(eventLimit: Int): Unit = {
    if (GraphInterpreter.Debug) println("---------------- EXECUTE")
    var eventsRemaining = eventLimit
    var connection = dequeue()
    while (eventsRemaining > 0 && connection != NoEvent) {
      try processEvent(connection)
      catch {
        case NonFatal(e) ⇒
          val stageId = connectionStates(connection) match {
            case Failed(ex)          ⇒ throw new IllegalStateException("Double fault. Failure while handling failure.", e)
            case Pulled              ⇒ assembly.outOwners(connection)
            case Completed           ⇒ assembly.inOwners(connection)
            case Cancelled           ⇒ assembly.outOwners(connection)
            case PushCompleted(elem) ⇒ assembly.inOwners(connection)
            case pushedElem          ⇒ assembly.inOwners(connection)
          }
          if (stageId == Boundary) throw e
          else logics(stageId).failStage(e)
      }
      eventsRemaining -= 1
      if (eventsRemaining > 0) connection = dequeue()
    }
    // TODO: deadlock detection
  }

  // Decodes and processes a single event for the given connection
  private def processEvent(connection: Int): Unit = {

    def processElement(elem: Any): Unit = {
      if (!isStageCompleted(assembly.inOwners(connection))) {
        if (GraphInterpreter.Debug) println(s"PUSH ${outOwnerName(connection)} -> ${inOwnerName(connection)}, $elem")
        inStates(connection) = Available
        inHandlers(connection).onPush()
      }
    }

    connectionStates(connection) match {
      case Pulled ⇒
        if (!isStageCompleted(assembly.outOwners(connection))) {
          if (GraphInterpreter.Debug) println(s"PULL ${inOwnerName(connection)} -> ${outOwnerName(connection)}")
          outStates(connection) = Available
          outHandlers(connection).onPull()
        }
      case Completed | CompletedHasElement(_) ⇒
        val stageId = assembly.inOwners(connection)
        if (!isStageCompleted(stageId) && inStates(connection) != Closed) {
          if (GraphInterpreter.Debug) println(s"COMPLETE ${outOwnerName(connection)} -> ${inOwnerName(connection)}")
          inStates(connection) = Closed
          inHandlers(connection).onUpstreamFinish()
          completeConnection(stageId)
        }
      case Failed(ex) ⇒
        val stageId = assembly.inOwners(connection)
        if (!isStageCompleted(stageId) && inStates(connection) != Closed) {
          if (GraphInterpreter.Debug) println(s"FAIL ${outOwnerName(connection)} -> ${inOwnerName(connection)}")
          inStates(connection) = Closed
          inHandlers(connection).onUpstreamFailure(ex)
          completeConnection(stageId)
        }
      case Cancelled ⇒
        val stageId = assembly.outOwners(connection)
        if (!isStageCompleted(stageId) && outStates(connection) != Closed) {
          if (GraphInterpreter.Debug) println(s"CANCEL ${inOwnerName(connection)} -> ${outOwnerName(connection)}")
          outStates(connection) = Closed
          outHandlers(connection).onDownstreamFinish()
          completeConnection(stageId)
        }
      case PushCompleted(elem) ⇒
        val stageId = assembly.inOwners(connection)
        if (!isStageCompleted(stageId) && inStates(connection) != Closed) {
          inStates(connection) = Available
          connectionStates(connection) = elem
          processElement(elem)
          val elemAfter = connectionStates(connection)
          if (elemAfter == Empty) enqueue(connection, Completed)
          else enqueue(connection, CompletedHasElement(elemAfter))
        } else {
          connectionStates(connection) = Completed
        }

      case pushedElem ⇒ processElement(pushedElem)

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

  private def enqueue(connection: Int, event: Any): Unit = {
    connectionStates(connection) = event
    eventQueue(queueTail & mask) = connection
    queueTail += 1
  }

  // Returns true if a connection has been completed *or if the completion event is already enqueued*. This is useful
  // to prevent redundant completion events in case of concurrent invocation on both sides of the connection.
  // I.e. when one side already enqueued the completion event, then the other side will not enqueue the event since
  // there is noone to process it anymore.
  def isConnectionCompleting(connection: Int): Boolean = connectionStates(connection).isInstanceOf[CompletingState]

  // Returns true if the given stage is alredy completed
  def isStageCompleted(stageId: Int): Boolean = stageId != Boundary && shutdownCounter(stageId) == 0

  private def isPushInFlight(connection: Int): Boolean =
    (inStates(connection) == InFlight) && // Other side has not been notified
      hasElement(connection)

  private def hasElement(connection: Int): Boolean =
    !connectionStates(connection).isInstanceOf[ConnectionState] &&
      connectionStates(connection) != Empty

  // Register that a connection in which the given stage participated has been completed and therefore the stage
  // itself might stop, too.
  private def completeConnection(stageId: Int): Unit = {
    if (stageId != Boundary) {
      val activeConnections = shutdownCounter(stageId)
      if (activeConnections > 0) {
        shutdownCounter(stageId) = activeConnections - 1
        // This was the last active connection keeping this stage alive
        if (activeConnections == 1) {
          runningStages -= 1
          logics(stageId).postStop()
          logics(stageId).afterPostStop()
        }
      }
    }
  }

  private[stream] def push(connection: Int, elem: Any): Unit = {
    if (!(inStates(connection) eq Closed)) {
      outStates(connection) = InFlight
      enqueue(connection, elem)
    }
  }

  private[stream] def pull(connection: Int): Unit = {
    if (!(outStates(connection) eq Closed)) {
      inStates(connection) = InFlight
      enqueue(connection, Pulled)
    }
  }

  private[stream] def complete(connection: Int): Unit = {
    outStates(connection) = Closed
    if (!isConnectionCompleting(connection) && (inStates(connection) ne Closed)) {
      if (hasElement(connection)) {
        // There is a pending push, we change the signal to be a PushCompleted (there can be only one signal in flight
        // for a connection)
        if (inStates(connection) == InFlight)
          connectionStates(connection) = PushCompleted(connectionStates(connection))
        else enqueue(connection, CompletedHasElement(connectionStates(connection)))
      } else enqueue(connection, Completed)
    }
    completeConnection(assembly.outOwners(connection))
  }

  private[stream] def fail(connection: Int, ex: Throwable): Unit = {
    outStates(connection) = Closed
    if (!isConnectionCompleting(connection) && (inStates(connection) ne Closed))
      enqueue(connection, Failed(ex))

    completeConnection(assembly.outOwners(connection))
  }

  private[stream] def cancel(connection: Int): Unit = {
    inStates(connection) = Closed
    if (!isConnectionCompleting(connection) && (outStates(connection) ne Closed))
      enqueue(connection, Cancelled)

    completeConnection(assembly.inOwners(connection))
  }

}