/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.stage

import akka.actor.{ Cancellable, DeadLetterSuppression }
import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.{ GraphModule, GraphInterpreter }
import akka.stream.impl.fusing.GraphInterpreter.GraphAssembly
import scala.collection.{ immutable, mutable }
import scala.concurrent.duration.FiniteDuration
import scala.collection.mutable.ArrayBuffer

abstract class GraphStageWithMaterializedValue[S <: Shape, M] extends Graph[S, M] {
  def shape: S
  def createLogicAndMaterializedValue: (GraphStageLogic, M)

  final override private[stream] lazy val module: Module =
    GraphModule(
      GraphAssembly(shape.inlets, shape.outlets, Array(this): _*),
      shape,
      Attributes.none)

  /**
   * This method throws an [[UnsupportedOperationException]] by default. The subclass can override this method
   * and provide a correct implementation that creates an exact copy of the stage with the provided new attributes.
   */
  final override def withAttributes(attr: Attributes): Graph[S, M] = new Graph[S, M] {
    override def shape = GraphStageWithMaterializedValue.this.shape
    override private[stream] def module = GraphStageWithMaterializedValue.this.module.withAttributes(attr)

    override def withAttributes(attr: Attributes) = GraphStageWithMaterializedValue.this.withAttributes(attr)
  }
}

/**
 * A GraphStage represents a reusable graph stream processing stage. A GraphStage consists of a [[Shape]] which describes
 * its input and output ports and a factory function that creates a [[GraphStageLogic]] which implements the processing
 * logic that ties the ports together.
 */
abstract class GraphStage[S <: Shape] extends GraphStageWithMaterializedValue[S, Unit] {
  final override def createLogicAndMaterializedValue = (createLogic, Unit)

  def createLogic: GraphStageLogic
}

/**
 * A SourceStage represents a reusable graph stream processing stage. A SourceStage consists of a [[akka.stream.Shape]] which describes
 * its output port.
 */
abstract class SourceStage[Out, M](name: String) extends GraphStageWithMaterializedValue[SourceShape[Out], M] {
  val out: Outlet[Out] = Outlet[Out](name + ".out")
  override val shape: SourceShape[Out] = SourceShape(out)
}

/**
 * A SinkStage represents a reusable graph stream processing stage. A SinkStage consists of a [[akka.stream.Shape]] which describes
 * its input port.
 */
abstract class SinkStage[In, M](name: String) extends GraphStageWithMaterializedValue[SinkShape[In], M] {
  val in: Inlet[In] = Inlet[In](name + ".in")
  override val shape: SinkShape[In] = SinkShape(in)
}

private object TimerMessages {
  final case class Scheduled(timerKey: Any, timerId: Int, repeating: Boolean) extends DeadLetterSuppression
  final case class Timer(id: Int, task: Cancellable)
}

object GraphStageLogic {
  /**
   * Input handler that terminates the stage upon receiving completion.
   * The stage fails upon receiving a failure.
   */
  class EagerTerminateInput extends InHandler {
    override def onPush(): Unit = ()
  }

  /**
   * Input handler that does not terminate the stage upon receiving completion.
   * The stage fails upon receiving a failure.
   */
  class IgnoreTerminateInput extends InHandler {
    override def onPush(): Unit = ()
    override def onUpstreamFinish(): Unit = ()
  }

  /**
   * Input handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  class ConditionalTerminateInput(predicate: () ⇒ Boolean) extends InHandler {
    override def onPush(): Unit = ()
    override def onUpstreamFinish(): Unit = if (predicate()) ownerStageLogic.completeStage()
  }

  /**
   * Input handler that does not terminate the stage upon receiving completion
   * nor failure.
   */
  class TotallyIgnorantInput extends InHandler {
    override def onPush(): Unit = ()
    override def onUpstreamFinish(): Unit = ()
    override def onUpstreamFailure(ex: Throwable): Unit = ()
  }

  /**
   * Output handler that terminates the stage upon cancellation.
   */
  class EagerTerminateOutput extends OutHandler {
    override def onPull(): Unit = ()
  }

  /**
   * Output handler that does not terminate the stage upon cancellation.
   */
  class IgnoreTerminateOutput extends OutHandler {
    override def onPull(): Unit = ()
    override def onDownstreamFinish(): Unit = ()
  }

  /**
   * Output handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  class ConditionalTerminateOutput(predicate: () ⇒ Boolean) extends OutHandler {
    override def onPull(): Unit = ()
    override def onDownstreamFinish(): Unit = if (predicate()) ownerStageLogic.completeStage()
  }

  private object DoNothing extends (() ⇒ Unit) {
    def apply(): Unit = ()
  }
}

/**
 * Represents the processing logic behind a [[GraphStage]]. Roughly speaking, a subclass of [[GraphStageLogic]] is a
 * collection of the following parts:
 *  * A set of [[InHandler]] and [[OutHandler]] instances and their assignments to the [[Inlet]]s and [[Outlet]]s
 *    of the enclosing [[GraphStage]]
 *  * Possible mutable state, accessible from the [[InHandler]] and [[OutHandler]] callbacks, but not from anywhere
 *    else (as such access would not be thread-safe)
 *  * The lifecycle hooks [[preStart()]] and [[postStop()]]
 *  * Methods for performing stream processing actions, like pulling or pushing elements
 *
 *  The stage logic is always stopped once all its input and output ports have been closed, i.e. it is not possible to
 *  keep the stage alive for further processing once it does not have any open ports.
 */
abstract class GraphStageLogic private[stream] (inCount: Int, outCount: Int) {
  import GraphInterpreter._
  import TimerMessages._
  import GraphStageLogic._

  def this(shape: Shape) = this(shape.inlets.size, shape.outlets.size)

  private val keyToTimers = mutable.Map[Any, Timer]()
  private val timerIdGen = Iterator from 1

  private var _timerAsyncCallback: AsyncCallback[Scheduled] = _
  private def getTimerAsyncCallback: AsyncCallback[Scheduled] = {
    if (_timerAsyncCallback eq null)
      _timerAsyncCallback = getAsyncCallback(onInternalTimer)

    _timerAsyncCallback
  }

  /**
   * INTERNAL API
   */
  private[stream] var stageId: Int = Int.MinValue

  /**
   * INTERNAL API
   */
  private[stream] var inHandlers = Array.ofDim[InHandler](inCount)
  /**
   * INTERNAL API
   */
  private[stream] var outHandlers = Array.ofDim[OutHandler](outCount)

  /**
   * INTERNAL API
   */
  private[stream] var inToConn = Array.ofDim[Int](inHandlers.length)
  /**
   * INTERNAL API
   */
  private[stream] var outToConn = Array.ofDim[Int](outHandlers.length)

  /**
   * INTERNAL API
   */
  private[this] var _interpreter: GraphInterpreter = _

  /**
   * INTENRAL API
   */
  private[stream] def interpreter_=(gi: GraphInterpreter) = _interpreter = gi

  /**
   * INTERNAL API
   */
  private[stream] def interpreter: GraphInterpreter =
    if (_interpreter == null)
      throw new IllegalStateException("not yet initialized: only setHandler is allowed in GraphStageLogic constructor")
    else _interpreter

  /**
   * Input handler that terminates the stage upon receiving completion.
   * The stage fails upon receiving a failure.
   */
  final protected def eagerTerminateInput: InHandler = new EagerTerminateInput
  /**
   * Input handler that does not terminate the stage upon receiving completion.
   * The stage fails upon receiving a failure.
   */
  final protected def ignoreTerminateInput: InHandler = new IgnoreTerminateInput
  /**
   * Input handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  final protected def conditionalTerminateInput(predicate: () ⇒ Boolean): InHandler = new ConditionalTerminateInput(predicate)
  /**
   * Input handler that does not terminate the stage upon receiving completion
   * nor failure.
   */
  final protected def totallyIgnorantInput: InHandler = new TotallyIgnorantInput
  /**
   * Output handler that terminates the stage upon cancellation.
   */
  final protected def eagerTerminateOutput: OutHandler = new EagerTerminateOutput
  /**
   * Output handler that does not terminate the stage upon cancellation.
   */
  final protected def ignoreTerminateOutput: OutHandler = new IgnoreTerminateOutput
  /**
   * Output handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  final protected def conditionalTerminateOutput(predicate: () ⇒ Boolean): OutHandler = new ConditionalTerminateOutput(predicate)

  /**
   * Assigns callbacks for the events for an [[Inlet]]
   */
  final protected def setHandler(in: Inlet[_], handler: InHandler): Unit = {
    handler.ownerStageLogic = this
    inHandlers(in.id) = handler
    if (_interpreter != null) _interpreter.setHandler(inToConn(in.id), handler)
  }

  /**
   * Retrieves the current callback for the events on the given [[Inlet]]
   */
  final protected def getHandler(in: Inlet[_]): InHandler = {
    inHandlers(in.id)
  }

  /**
   * Assigns callbacks for the events for an [[Outlet]]
   */
  final protected def setHandler(out: Outlet[_], handler: OutHandler): Unit = {
    handler.ownerStageLogic = this
    outHandlers(out.id) = handler
    if (_interpreter != null) _interpreter.setHandler(outToConn(out.id), handler)
  }

  /**
   * Retrieves the current callback for the events on the given [[Outlet]]
   */
  final protected def getHandler(out: Outlet[_]): OutHandler = {
    outHandlers(out.id)
  }

  private def getNonEmittingHandler(out: Outlet[_]): OutHandler =
    getHandler(out) match {
      case e: Emitting[_] ⇒ e.previous
      case other          ⇒ other
    }

  private def conn[T](in: Inlet[T]): Int = inToConn(in.id)
  private def conn[T](out: Outlet[T]): Int = outToConn(out.id)

  /**
   * Requests an element on the given port. Calling this method twice before an element arrived will fail.
   * There can only be one outstanding request at any given time. The method [[hasBeenPulled()]] can be used
   * query whether pull is allowed to be called or not. This method will also fail if the port is already closed.
   */
  final protected def pull[T](in: Inlet[T]): Unit = {
    if ((interpreter.portStates(conn(in)) & (InReady | InClosed)) == InReady) {
      interpreter.pull(conn(in))
    } else {
      // Detailed error information should not add overhead to the hot path
      require(!isClosed(in), "Cannot pull closed port")
      require(!hasBeenPulled(in), "Cannot pull port twice")
    }
  }

  /**
   * Requests an element on the given port unless the port is already closed.
   * Calling this method twice before an element arrived will fail.
   * There can only be one outstanding request at any given time. The method [[hasBeenPulled()]] can be used
   * query whether pull is allowed to be called or not.
   */
  final protected def tryPull[T](in: Inlet[T]): Unit = if (!isClosed(in)) pull(in)

  /**
   * Requests to stop receiving events from a given input port. Cancelling clears any ungrabbed elements from the port.
   */
  final protected def cancel[T](in: Inlet[T]): Unit = interpreter.cancel(conn(in))

  /**
   * Once the callback [[InHandler.onPush()]] for an input port has been invoked, the element that has been pushed
   * can be retrieved via this method. After [[grab()]] has been called the port is considered to be empty, and further
   * calls to [[grab()]] will fail until the port is pulled again and a new element is pushed as a response.
   *
   * The method [[isAvailable()]] can be used to query if the port has an element that can be grabbed or not.
   */
  final protected def grab[T](in: Inlet[T]): T = {
    val connection = conn(in)
    // Fast path
    if ((interpreter.portStates(connection) & (InReady | InFailed)) == InReady &&
      (interpreter.connectionSlots(connection).asInstanceOf[AnyRef] ne Empty)) {
      val elem = interpreter.connectionSlots(connection)
      interpreter.connectionSlots(connection) = Empty
      elem.asInstanceOf[T]
    } else {
      // Slow path
      require(isAvailable(in), "Cannot get element from already empty input port")
      val failed = interpreter.connectionSlots(connection).asInstanceOf[Failed]
      val elem = failed.previousElem.asInstanceOf[T]
      interpreter.connectionSlots(connection) = Failed(failed.ex, Empty)
      elem
    }
  }

  /**
   * Indicates whether there is already a pending pull for the given input port. If this method returns true
   * then [[isAvailable()]] must return false for that same port.
   */
  final protected def hasBeenPulled[T](in: Inlet[T]): Boolean = (interpreter.portStates(conn(in)) & (InReady | InClosed)) == 0

  /**
   * Indicates whether there is an element waiting at the given input port. [[grab()]] can be used to retrieve the
   * element. After calling [[grab()]] this method will return false.
   *
   * If this method returns true then [[hasBeenPulled()]] will return false for that same port.
   */
  final protected def isAvailable[T](in: Inlet[T]): Boolean = {
    val connection = conn(in)

    val normalArrived = (interpreter.portStates(conn(in)) & (InReady | InFailed)) == InReady

    // Fast path
    if (normalArrived) interpreter.connectionSlots(connection).asInstanceOf[AnyRef] ne Empty
    else {
      // Slow path on failure
      if ((interpreter.portStates(conn(in)) & (InReady | InFailed)) == (InReady | InFailed)) {
        interpreter.connectionSlots(connection) match {
          case Failed(_, elem) ⇒ elem.asInstanceOf[AnyRef] ne Empty
          case _               ⇒ false // This can only be Empty actually (if a cancel was concurrent with a failure)
        }
      } else false
    }
  }

  /**
   * Indicates whether the port has been closed. A closed port cannot be pulled.
   */
  final protected def isClosed[T](in: Inlet[T]): Boolean = (interpreter.portStates(conn(in)) & InClosed) != 0

  /**
   * Emits an element through the given output port. Calling this method twice before a [[pull()]] has been arrived
   * will fail. There can be only one outstanding push request at any given time. The method [[isAvailable()]] can be
   * used to check if the port is ready to be pushed or not.
   */
  final protected def push[T](out: Outlet[T], elem: T): Unit = {
    if ((interpreter.portStates(conn(out)) & (OutReady | OutClosed)) == OutReady) {
      interpreter.push(conn(out), elem)
    } else {
      // Detailed error information should not add overhead to the hot path
      require(isAvailable(out), "Cannot push port twice")
      require(!isClosed(out), "Cannot pull closed port")
    }
  }

  /**
   * Signals that there will be no more elements emitted on the given port.
   */
  final protected def complete[T](out: Outlet[T]): Unit = interpreter.complete(conn(out))

  /**
   * Signals failure through the given port.
   */
  final protected def fail[T](out: Outlet[T], ex: Throwable): Unit = interpreter.fail(conn(out), ex)

  /**
   * Automatically invokes [[cancel()]] or [[complete()]] on all the input or output ports that have been called,
   * then stops the stage, then [[postStop()]] is called.
   */
  final def completeStage(): Unit = {
    var i = 0
    while (i < inToConn.length) {
      interpreter.cancel(inToConn(i))
      i += 1
    }
    i = 0
    while (i < outToConn.length) {
      interpreter.complete(outToConn(i))
      i += 1
    }
  }

  /**
   * Automatically invokes [[cancel()]] or [[fail()]] on all the input or output ports that have been called,
   * then stops the stage, then [[postStop()]] is called.
   */
  final def failStage(ex: Throwable): Unit = {
    var i = 0
    while (i < inToConn.length) {
      interpreter.cancel(inToConn(i))
      i += 1
    }
    i = 0
    while (i < outToConn.length) {
      interpreter.fail(outToConn(i), ex)
      i += 1
    }
  }

  /**
   * Return true if the given output port is ready to be pushed.
   */
  final def isAvailable[T](out: Outlet[T]): Boolean =
    (interpreter.portStates(conn(out)) & (OutReady | OutClosed)) == OutReady

  /**
   * Indicates whether the port has been closed. A closed port cannot be pushed.
   */
  final protected def isClosed[T](out: Outlet[T]): Boolean = (interpreter.portStates(conn(out)) & OutClosed) != 0

  /**
   * Read a number of elements from the given inlet and continue with the given function,
   * suspending execution if necessary. This action replaces the [[InHandler]]
   * for the given inlet if suspension is needed and reinstalls the current
   * handler upon receiving the last `onPush()` signal (before invoking the `andThen` function).
   */
  final protected def readN[T](in: Inlet[T], n: Int)(andThen: Seq[T] ⇒ Unit): Unit =
    if (n < 0) throw new IllegalArgumentException("cannot read negative number of elements")
    else if (n == 0) andThen(Nil)
    else {
      val result = new ArrayBuffer[T](n)
      var pos = 0
      if (isAvailable(in)) {
        val elem = grab(in)
        result(0) = elem
        if (n == 1) {
          andThen(result)
        } else {
          pos = 1
          requireNotReading(in)
          pull(in)
          setHandler(in, new Reading(in, n - 1, getHandler(in))(elem ⇒ {
            result(pos) = elem
            pos += 1
            if (pos == n) andThen(result)
          }))
        }
      } else {
        requireNotReading(in)
        if (!hasBeenPulled(in)) pull(in)
        setHandler(in, new Reading(in, n, getHandler(in))(elem ⇒ {
          result(pos) = elem
          pos += 1
          if (pos == n) andThen(result)
        }))
      }
    }

  /**
   * Read an element from the given inlet and continue with the given function,
   * suspending execution if necessary. This action replaces the [[InHandler]]
   * for the given inlet if suspension is needed and reinstalls the current
   * handler upon receiving the `onPush()` signal (before invoking the `andThen` function).
   */
  final protected def read[T](in: Inlet[T])(andThen: T ⇒ Unit): Unit = {
    if (isAvailable(in)) {
      val elem = grab(in)
      andThen(elem)
    } else {
      requireNotReading(in)
      if (!hasBeenPulled(in)) pull(in)
      setHandler(in, new Reading(in, 1, getHandler(in))(andThen))
    }
  }

  /**
   * Abort outstanding (suspended) reading for the given inlet, if there is any.
   * This will reinstall the replaced handler that was in effect before the `read`
   * call.
   */
  final protected def abortReading(in: Inlet[_]): Unit =
    getHandler(in) match {
      case r: Reading[_] ⇒
        setHandler(in, r.previous)
      case _ ⇒
    }

  private def requireNotReading(in: Inlet[_]): Unit =
    if (getHandler(in).isInstanceOf[Reading[_]])
      throw new IllegalStateException("already reading on inlet " + in)

  /**
   * Caution: for n==1 andThen is called after resetting the handler, for
   * other values it is called without resetting the handler.
   */
  private class Reading[T](in: Inlet[T], private var n: Int, val previous: InHandler)(andThen: T ⇒ Unit) extends InHandler {
    override def onPush(): Unit = {
      val elem = grab(in)
      if (n == 1) setHandler(in, previous)
      else {
        n -= 1
        pull(in)
      }
      andThen(elem)
    }
    override def onUpstreamFinish(): Unit = previous.onUpstreamFinish()
    override def onUpstreamFailure(ex: Throwable): Unit = previous.onUpstreamFailure(ex)
  }

  /**
   * Emit a sequence of elements through the given outlet and continue with the given thunk
   * afterwards, suspending execution if necessary.
   * This action replaces the [[OutHandler]] for the given outlet if suspension
   * is needed and reinstalls the current handler upon receiving an `onPull()`
   * signal (before invoking the `andThen` function).
   */
  final protected def emitMultiple[T](out: Outlet[T], elems: immutable.Iterable[T], andThen: () ⇒ Unit): Unit =
    emitMultiple(out, elems.iterator, andThen)

  /**
   * Emit a sequence of elements through the given outlet, suspending execution if necessary.
   * This action replaces the [[OutHandler]] for the given outlet if suspension
   * is needed and reinstalls the current handler upon receiving an `onPull()`
   * signal.
   */
  final protected def emitMultiple[T](out: Outlet[T], elems: immutable.Iterable[T]): Unit = emitMultiple(out, elems, DoNothing)

  /**
   * Emit a sequence of elements through the given outlet and continue with the given thunk
   * afterwards, suspending execution if necessary.
   * This action replaces the [[OutHandler]] for the given outlet if suspension
   * is needed and reinstalls the current handler upon receiving an `onPull()`
   * signal (before invoking the `andThen` function).
   */
  final protected def emitMultiple[T](out: Outlet[T], elems: Iterator[T], andThen: () ⇒ Unit): Unit =
    if (elems.hasNext) {
      if (isAvailable(out)) {
        push(out, elems.next())
        if (elems.hasNext)
          setOrAddEmitting(out, new EmittingIterator(out, elems, getNonEmittingHandler(out), andThen))
        else andThen()
      } else {
        setOrAddEmitting(out, new EmittingIterator(out, elems, getNonEmittingHandler(out), andThen))
      }
    }

  /**
   * Emit a sequence of elements through the given outlet, suspending execution if necessary.
   * This action replaces the [[OutHandler]] for the given outlet if suspension
   * is needed and reinstalls the current handler upon receiving an `onPull()`
   * signal.
   */
  final protected def emitMultipe[T](out: Outlet[T], elems: Iterator[T]): Unit = emitMultiple(out, elems, DoNothing)

  /**
   * Emit an element through the given outlet and continue with the given thunk
   * afterwards, suspending execution if necessary.
   * This action replaces the [[OutHandler]] for the given outlet if suspension
   * is needed and reinstalls the current handler upon receiving an `onPull()`
   * signal (before invoking the `andThen` function).
   */
  final protected def emit[T](out: Outlet[T], elem: T, andThen: () ⇒ Unit): Unit =
    if (isAvailable(out)) {
      push(out, elem)
      andThen()
    } else {
      setOrAddEmitting(out, new EmittingSingle(out, elem, getNonEmittingHandler(out), andThen))
    }

  /**
   * Emit an element through the given outlet, suspending execution if necessary.
   * This action replaces the [[OutHandler]] for the given outlet if suspension
   * is needed and reinstalls the current handler upon receiving an `onPull()`
   * signal.
   */
  final protected def emit[T](out: Outlet[T], elem: T): Unit = emit(out, elem, DoNothing)

  /**
   * Abort outstanding (suspended) emissions for the given outlet, if there are any.
   * This will reinstall the replaced handler that was in effect before the `emit`
   * call.
   */
  final protected def abortEmitting(out: Outlet[_]): Unit =
    getHandler(out) match {
      case e: Emitting[_] ⇒ setHandler(out, e.previous)
      case _              ⇒
    }

  private def setOrAddEmitting[T](out: Outlet[T], next: Emitting[T]): Unit =
    getHandler(out) match {
      case e: Emitting[_] ⇒ e.asInstanceOf[Emitting[T]].addFollowUp(next)
      case _              ⇒ setHandler(out, next)
    }

  private abstract class Emitting[T](protected val out: Outlet[T], val previous: OutHandler, andThen: () ⇒ Unit) extends OutHandler {
    private var followUps: mutable.Queue[Emitting[T]] = null

    protected def followUp(): Unit = {
      setHandler(out, previous)
      andThen()
      if (followUps != null) {
        val next = followUps.dequeue()
        if (followUps.nonEmpty) next.followUps = followUps
        setHandler(out, next)
      }
    }

    def addFollowUp(e: Emitting[T]): Unit =
      if (followUps == null) followUps = mutable.Queue(e)
      else followUps.enqueue(e)

    override def onDownstreamFinish(): Unit = previous.onDownstreamFinish()
  }

  private class EmittingSingle[T](_out: Outlet[T], elem: T, _previous: OutHandler, _andThen: () ⇒ Unit)
    extends Emitting(_out, _previous, _andThen) {

    override def onPull(): Unit = {
      push(out, elem)
      followUp()
    }
  }

  private class EmittingIterator[T](_out: Outlet[T], elems: Iterator[T], _previous: OutHandler, _andThen: () ⇒ Unit)
    extends Emitting(_out, _previous, _andThen) {

    override def onPull(): Unit = {
      push(out, elems.next())
      if (!elems.hasNext) {
        followUp()
      }
    }
  }

  /**
   * Install a handler on the given inlet that emits received elements on the
   * given outlet before pulling for more data. `doTerminate` controls whether
   * completion or failure of the given inlet shall lead to stage termination or not.
   */
  final protected def passAlong[Out, In <: Out](from: Inlet[In], to: Outlet[Out], doFinish: Boolean, doFail: Boolean): Unit =
    setHandler(from, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(from)
        emit(to, elem, () ⇒ tryPull(from))
      }
      override def onUpstreamFinish(): Unit = if (doFinish) super.onUpstreamFinish()
      override def onUpstreamFailure(ex: Throwable): Unit = if (doFail) super.onUpstreamFailure(ex)
    })

  /**
   * Obtain a callback object that can be used asynchronously to re-enter the
   * current [[AsyncStage]] with an asynchronous notification. The [[invoke()]] method of the returned
   * [[AsyncCallback]] is safe to be called from other threads and it will in the background thread-safely
   * delegate to the passed callback function. I.e. [[invoke()]] will be called by the external world and
   * the passed handler will be invoked eventually in a thread-safe way by the execution environment.
   *
   * This object can be cached and reused within the same [[GraphStageLogic]].
   */
  final def getAsyncCallback[T](handler: T ⇒ Unit): AsyncCallback[T] = {
    new AsyncCallback[T] {
      override def invoke(event: T): Unit =
        interpreter.onAsyncInput(GraphStageLogic.this, event, handler.asInstanceOf[Any ⇒ Unit])
    }
  }

  private def onInternalTimer(scheduled: Scheduled): Unit = {
    val Id = scheduled.timerId
    keyToTimers.get(scheduled.timerKey) match {
      case Some(Timer(Id, _)) ⇒
        if (!scheduled.repeating) keyToTimers -= scheduled.timerKey
        onTimer(scheduled.timerKey)
      case _ ⇒
    }
  }

  /**
   * Schedule timer to call [[#onTimer]] periodically with the given interval.
   * Any existing timer with the same key will automatically be canceled before
   * adding the new timer.
   */
  final protected def schedulePeriodically(timerKey: Any, interval: FiniteDuration): Unit =
    schedulePeriodicallyWithInitialDelay(timerKey, interval, interval)

  /**
   * Schedule timer to call [[#onTimer]] periodically with the given interval after the specified
   * initial delay.
   * Any existing timer with the same key will automatically be canceled before
   * adding the new timer.
   */
  final protected def schedulePeriodicallyWithInitialDelay(
    timerKey: Any,
    initialDelay: FiniteDuration,
    interval: FiniteDuration): Unit = {
    cancelTimer(timerKey)
    val id = timerIdGen.next()
    val task = interpreter.materializer.schedulePeriodically(initialDelay, interval, new Runnable {
      def run() = getTimerAsyncCallback.invoke(Scheduled(timerKey, id, repeating = true))
    })
    keyToTimers(timerKey) = Timer(id, task)
  }

  /**
   * Schedule timer to call [[#onTimer]] after given delay.
   * Any existing timer with the same key will automatically be canceled before
   * adding the new timer.
   */
  final protected def scheduleOnce(timerKey: Any, delay: FiniteDuration): Unit = {
    cancelTimer(timerKey)
    val id = timerIdGen.next()
    val task = interpreter.materializer.scheduleOnce(delay, new Runnable {
      def run() = getTimerAsyncCallback.invoke(Scheduled(timerKey, id, repeating = false))
    })
    keyToTimers(timerKey) = Timer(id, task)
  }

  /**
   * Cancel timer, ensuring that the [[#onTimer]] is not subsequently called.
   * @param timerKey key of the timer to cancel
   */
  final protected def cancelTimer(timerKey: Any): Unit =
    keyToTimers.get(timerKey).foreach { t ⇒
      t.task.cancel()
      keyToTimers -= timerKey
    }

  /**
   * Inquire whether the timer is still active. Returns true unless the
   * timer does not exist, has previously been canceled or if it was a
   * single-shot timer that was already triggered.
   */
  final protected def isTimerActive(timerKey: Any): Boolean = keyToTimers contains timerKey

  /**
   * Will be called when the scheduled timer is triggered.
   * @param timerKey key of the scheduled timer
   */
  protected def onTimer(timerKey: Any): Unit = ()

  // Internal hooks to avoid reliance on user calling super in preStart
  protected[stream] def beforePreStart(): Unit = {
  }

  // Internal hooks to avoid reliance on user calling super in postStop
  protected[stream] def afterPostStop(): Unit = {
    keyToTimers.foreach { case (_, Timer(_, task)) ⇒ task.cancel() }
    keyToTimers.clear()
  }

  /**
   * Invoked before any external events are processed, at the startup of the stage.
   */
  def preStart(): Unit = ()

  /**
   * Invoked after processing of external events stopped because the stage is about to stop or fail.
   */
  def postStop(): Unit = ()
}

/**
 * Collection of callbacks for an input port of a [[GraphStage]]
 */
trait InHandler {
  /**
   * INTERNAL API
   */
  private[stream] var ownerStageLogic: GraphStageLogic = _

  /**
   * Called when the input port has a new element available. The actual element can be retrieved via the
   * [[GraphStageLogic.grab()]] method.
   */
  def onPush(): Unit

  /**
   * Called when the input port is finished. After this callback no other callbacks will be called for this port.
   */
  def onUpstreamFinish(): Unit = ownerStageLogic.completeStage()

  /**
   * Called when the input port has failed. After this callback no other callbacks will be called for this port.
   */
  def onUpstreamFailure(ex: Throwable): Unit = ownerStageLogic.failStage(ex)
}

/**
 * Collection of callbacks for an output port of a [[GraphStage]]
 */
trait OutHandler {
  /**
   * INTERNAL API
   */
  private[stream] var ownerStageLogic: GraphStageLogic = _

  /**
   * Called when the output port has received a pull, and therefore ready to emit an element, i.e. [[GraphStageLogic.push()]]
   * is now allowed to be called on this port.
   */
  def onPull(): Unit

  /**
   * Called when the output port will no longer accept any new elements. After this callback no other callbacks will
   * be called for this port.
   */
  def onDownstreamFinish(): Unit = ownerStageLogic.completeStage()
}