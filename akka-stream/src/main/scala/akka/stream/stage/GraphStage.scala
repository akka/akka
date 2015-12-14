/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.stage

import java.util.concurrent.atomic.AtomicReference

import akka.actor._
import akka.dispatch.sysmsg.{ DeathWatchNotification, SystemMessage, Unwatch, Watch }
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.GraphInterpreter.GraphAssembly
import akka.stream.impl.fusing.{ GraphInterpreter, GraphModule }
import akka.stream.impl.{ ReactiveStreamsCompliance, SeqActorName }

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.collection.{ immutable, mutable }
import scala.concurrent.duration.FiniteDuration

abstract class GraphStageWithMaterializedValue[+S <: Shape, +M] extends Graph[S, M] {

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, M)

  final override private[stream] lazy val module: Module =
    GraphModule(
      GraphAssembly(shape.inlets, shape.outlets, this),
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
  final override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) =
    (createLogic(inheritedAttributes), Unit)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic
}

private object TimerMessages {
  final case class Scheduled(timerKey: Any, timerId: Int, repeating: Boolean) extends DeadLetterSuppression
  final case class Timer(id: Int, task: Cancellable)
}

object GraphStageLogic {
  final case class StageActorRefNotInitializedException()
    extends RuntimeException("You must first call getStageActorRef, to initialize the Actors behaviour")

  /**
   * Input handler that terminates the stage upon receiving completion.
   * The stage fails upon receiving a failure.
   */
  object EagerTerminateInput extends InHandler {
    override def onPush(): Unit = ()
  }

  /**
   * Input handler that does not terminate the stage upon receiving completion.
   * The stage fails upon receiving a failure.
   */
  object IgnoreTerminateInput extends InHandler {
    override def onPush(): Unit = ()
    override def onUpstreamFinish(): Unit = ()
  }

  /**
   * Input handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  class ConditionalTerminateInput(predicate: () ⇒ Boolean) extends InHandler {
    override def onPush(): Unit = ()
    override def onUpstreamFinish(): Unit =
      if (predicate()) GraphInterpreter.currentInterpreter.activeStage.completeStage()
  }

  /**
   * Input handler that does not terminate the stage upon receiving completion
   * nor failure.
   */
  object TotallyIgnorantInput extends InHandler {
    override def onPush(): Unit = ()
    override def onUpstreamFinish(): Unit = ()
    override def onUpstreamFailure(ex: Throwable): Unit = ()
  }

  /**
   * Output handler that terminates the stage upon cancellation.
   */
  object EagerTerminateOutput extends OutHandler {
    override def onPull(): Unit = ()
  }

  /**
   * Output handler that does not terminate the stage upon cancellation.
   */
  object IgnoreTerminateOutput extends OutHandler {
    override def onPull(): Unit = ()
    override def onDownstreamFinish(): Unit = ()
  }

  /**
   * Output handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  class ConditionalTerminateOutput(predicate: () ⇒ Boolean) extends OutHandler {
    override def onPull(): Unit = ()
    override def onDownstreamFinish(): Unit =
      if (predicate()) GraphInterpreter.currentInterpreter.activeStage.completeStage()
  }

  private object DoNothing extends (() ⇒ Unit) {
    def apply(): Unit = ()
  }

  /**
   * Minimal actor to work with other actors and watch them in a synchronous ways
   */
  final class StageActorRef(val provider: ActorRefProvider, val log: LoggingAdapter,
                            getAsyncCallback: StageActorRef.Receive ⇒ AsyncCallback[(ActorRef, Any)],
                            initialReceive: StageActorRef.Receive,
                            override val path: ActorPath) extends akka.actor.MinimalActorRef {
    import StageActorRef._

    private val callback = getAsyncCallback(internalReceive)

    @volatile
    private[this] var behaviour = initialReceive

    /** INTERNAL API */
    private[akka] def internalReceive(pack: (ActorRef, Any)): Unit =
      behaviour(pack)

    override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = {
      message match {
        case m @ (PoisonPill | Kill) ⇒
          log.warning("{} message sent to StageActorRef({}) will be ignored, since it is not a real Actor." +
            "Use a custom message type to communicate with it instead.", m, path)
        case _ ⇒
          callback.invoke((sender, message))
      }
    }

    override def sendSystemMessage(message: SystemMessage): Unit = message match {
      case w: Watch   ⇒ addWatcher(w.watchee, w.watcher)
      case u: Unwatch ⇒ remWatcher(u.watchee, u.watcher)
      case DeathWatchNotification(actorRef, _, _) ⇒
        this.!(Terminated(actorRef)(existenceConfirmed = true, addressTerminated = false))
      case _ ⇒ //ignore all other messages
    }

    /**
     * Special `become` allowing to swap the behaviour of this StageActorRef.
     * Unbecome is not available.
     */
    def become(receive: StageActorRef.Receive): Unit = {
      behaviour = receive
    }

    private[this] val _watchedBy = new AtomicReference[Set[ActorRef]](ActorCell.emptyActorRefSet)

    override def isTerminated = _watchedBy.get() == StageTerminatedTombstone

    //noinspection EmptyCheck
    protected def sendTerminated(): Unit = {
      val watchedBy = _watchedBy.getAndSet(StageTerminatedTombstone)
      if (!(watchedBy == StageTerminatedTombstone) && !watchedBy.isEmpty) {

        watchedBy foreach sendTerminated(ifLocal = false)
        watchedBy foreach sendTerminated(ifLocal = true)
      }
    }
    private def sendTerminated(ifLocal: Boolean)(watcher: ActorRef): Unit =
      if (watcher.asInstanceOf[ActorRefScope].isLocal == ifLocal)
        watcher.asInstanceOf[InternalActorRef].sendSystemMessage(DeathWatchNotification(this, existenceConfirmed = true, addressTerminated = false))

    override def stop(): Unit = sendTerminated()

    @tailrec final def addWatcher(watchee: ActorRef, watcher: ActorRef): Unit =
      _watchedBy.get() match {
        case StageTerminatedTombstone ⇒
          sendTerminated(ifLocal = true)(watcher)
          sendTerminated(ifLocal = false)(watcher)

        case watchedBy ⇒
          val watcheeSelf = watchee == this
          val watcherSelf = watcher == this

          if (watcheeSelf && !watcherSelf) {
            if (!watchedBy.contains(watcher))
              if (!_watchedBy.compareAndSet(watchedBy, watchedBy + watcher))
                addWatcher(watchee, watcher) // try again
          } else if (!watcheeSelf && watcherSelf) {
            watch(watchee)
          } else {
            log.error("BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, this))
          }
      }

    @tailrec final def remWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
      _watchedBy.get() match {
        case StageTerminatedTombstone ⇒ // do nothing...
        case watchedBy ⇒
          val watcheeSelf = watchee == this
          val watcherSelf = watcher == this

          if (watcheeSelf && !watcherSelf) {
            if (watchedBy.contains(watcher))
              if (!_watchedBy.compareAndSet(watchedBy, watchedBy - watcher))
                remWatcher(watchee, watcher) // try again
          } else if (!watcheeSelf && watcherSelf) {
            unwatch(watchee)
          } else {
            log.error("BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, this))
          }
      }
    }

    def watch(actorRef: ActorRef): Unit =
      actorRef.asInstanceOf[InternalActorRef].sendSystemMessage(Watch(actorRef.asInstanceOf[InternalActorRef], this))

    def unwatch(actorRef: ActorRef): Unit =
      actorRef.asInstanceOf[InternalActorRef].sendSystemMessage(Unwatch(actorRef.asInstanceOf[InternalActorRef], this))
  }
  object StageActorRef {
    type Receive = ((ActorRef, Any)) ⇒ Unit

    val StageTerminatedTombstone = null

    // globally sequential, one should not depend on these names in any case
    val name = SeqActorName("StageActorRef")
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
 *  The stage logic is always once all its input and output ports have been closed, i.e. it is not possible to
 *  keep the stage alive for further processing once it does not have any open ports. This can be changed by
 *  overriding `keepGoingAfterAllPortsClosed` to return true.
 */
abstract class GraphStageLogic private[stream] (val inCount: Int, val outCount: Int) {
  import GraphInterpreter._
  import GraphStageLogic._

  def this(shape: Shape) = this(shape.inlets.size, shape.outlets.size)
  /**
   * INTERNAL API
   */
  private[stream] var stageId: Int = Int.MinValue

  /**
   * INTERNAL API
   */
  // Using common array to reduce overhead for small port counts
  private[stream] val handlers = Array.ofDim[Any](inCount + outCount)

  /**
   * INTERNAL API
   */
  // Using common array to reduce overhead for small port counts
  private[stream] val portToConn = Array.ofDim[Int](handlers.length)

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
  final protected def eagerTerminateInput: InHandler = EagerTerminateInput
  /**
   * Input handler that does not terminate the stage upon receiving completion.
   * The stage fails upon receiving a failure.
   */
  final protected def ignoreTerminateInput: InHandler = IgnoreTerminateInput
  /**
   * Input handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  final protected def conditionalTerminateInput(predicate: () ⇒ Boolean): InHandler = new ConditionalTerminateInput(predicate)
  /**
   * Input handler that does not terminate the stage upon receiving completion
   * nor failure.
   */
  final protected def totallyIgnorantInput: InHandler = TotallyIgnorantInput
  /**
   * Output handler that terminates the stage upon cancellation.
   */
  final protected def eagerTerminateOutput: OutHandler = EagerTerminateOutput
  /**
   * Output handler that does not terminate the stage upon cancellation.
   */
  final protected def ignoreTerminateOutput: OutHandler = IgnoreTerminateOutput
  /**
   * Output handler that terminates the state upon receiving completion if the
   * given condition holds at that time. The stage fails upon receiving a failure.
   */
  final protected def conditionalTerminateOutput(predicate: () ⇒ Boolean): OutHandler = new ConditionalTerminateOutput(predicate)

  /**
   * Assigns callbacks for the events for an [[Inlet]]
   */
  final protected def setHandler(in: Inlet[_], handler: InHandler): Unit = {
    handlers(in.id) = handler
    if (_interpreter != null) _interpreter.setHandler(conn(in), handler)
  }

  /**
   * Retrieves the current callback for the events on the given [[Inlet]]
   */
  final protected def getHandler(in: Inlet[_]): InHandler = {
    handlers(in.id).asInstanceOf[InHandler]
  }

  /**
   * Assigns callbacks for the events for an [[Outlet]]
   */
  final protected def setHandler(out: Outlet[_], handler: OutHandler): Unit = {
    handlers(out.id + inCount) = handler
    if (_interpreter != null) _interpreter.setHandler(conn(out), handler)
  }

  private def conn(in: Inlet[_]): Int = portToConn(in.id)
  private def conn(out: Outlet[_]): Int = portToConn(out.id + inCount)

  /**
   * Retrieves the current callback for the events on the given [[Outlet]]
   */
  final protected def getHandler(out: Outlet[_]): OutHandler = {
    handlers(out.id + inCount).asInstanceOf[OutHandler]
  }

  private def getNonEmittingHandler(out: Outlet[_]): OutHandler =
    getHandler(out) match {
      case e: Emitting[_] ⇒ e.previous
      case other          ⇒ other
    }

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
    if ((interpreter.portStates(conn(out)) & (OutReady | OutClosed)) == OutReady && (elem != null)) {
      interpreter.push(conn(out), elem)
    } else {
      // Detailed error information should not add overhead to the hot path
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      require(isAvailable(out), "Cannot push port twice")
      require(!isClosed(out), "Cannot pull closed port")
    }
  }

  /**
   * Signals that there will be no more elements emitted on the given port.
   */
  final protected def complete[T](out: Outlet[T]): Unit =
    getHandler(out) match {
      case e: Emitting[_] ⇒ e.addFollowUp(new EmittingCompletion(e.out, e.previous))
      case _              ⇒ interpreter.complete(conn(out))
    }

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
    while (i < portToConn.length) {
      if (i < inCount)
        interpreter.cancel(portToConn(i))
      else handlers(i) match {
        case e: Emitting[_] ⇒ e.addFollowUp(new EmittingCompletion(e.out, e.previous))
        case _              ⇒ interpreter.complete(portToConn(i))
      }
      i += 1
    }
    if (keepGoingAfterAllPortsClosed) interpreter.closeKeptAliveStageIfNeeded(stageId)
  }

  /**
   * Automatically invokes [[cancel()]] or [[fail()]] on all the input or output ports that have been called,
   * then stops the stage, then [[postStop()]] is called.
   */
  final def failStage(ex: Throwable): Unit = {
    var i = 0
    while (i < portToConn.length) {
      if (i < inCount)
        interpreter.cancel(portToConn(i))
      else
        interpreter.fail(portToConn(i), ex)
      i += 1
    }
    if (keepGoingAfterAllPortsClosed) interpreter.closeKeptAliveStageIfNeeded(stageId)
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
  final protected def readN[T](in: Inlet[T], n: Int)(andThen: Seq[T] ⇒ Unit, onClose: Seq[T] ⇒ Unit): Unit =
    if (n < 0) throw new IllegalArgumentException("cannot read negative number of elements")
    else if (n == 0) andThen(Nil)
    else {
      val result = new ArrayBuffer[T](n)
      var pos = 0
      def realAndThen = (elem: T) ⇒ {
        result(pos) = elem
        pos += 1
        if (pos == n) andThen(result)
      }
      def realOnClose = () ⇒ onClose(result.take(pos))

      if (isAvailable(in)) {
        val elem = grab(in)
        result(0) = elem
        if (n == 1) {
          andThen(result)
        } else {
          pos = 1
          requireNotReading(in)
          pull(in)
          setHandler(in, new Reading(in, n - 1, getHandler(in))(realAndThen, realOnClose))
        }
      } else {
        requireNotReading(in)
        if (!hasBeenPulled(in)) pull(in)
        setHandler(in, new Reading(in, n, getHandler(in))(realAndThen, realOnClose))
      }
    }

  /**
   * Read an element from the given inlet and continue with the given function,
   * suspending execution if necessary. This action replaces the [[InHandler]]
   * for the given inlet if suspension is needed and reinstalls the current
   * handler upon receiving the `onPush()` signal (before invoking the `andThen` function).
   */
  final protected def read[T](in: Inlet[T])(andThen: T ⇒ Unit, onClose: () ⇒ Unit): Unit = {
    if (isAvailable(in)) {
      val elem = grab(in)
      andThen(elem)
    } else if (isClosed(in)) {
      onClose()
    } else {
      requireNotReading(in)
      if (!hasBeenPulled(in)) pull(in)
      setHandler(in, new Reading(in, 1, getHandler(in))(andThen, onClose))
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
  private class Reading[T](in: Inlet[T], private var n: Int, val previous: InHandler)(andThen: T ⇒ Unit, onClose: () ⇒ Unit) extends InHandler {
    override def onPush(): Unit = {
      val elem = grab(in)
      if (n == 1) setHandler(in, previous)
      else {
        n -= 1
        pull(in)
      }
      andThen(elem)
    }
    override def onUpstreamFinish(): Unit = {
      setHandler(in, previous)
      onClose()
      previous.onUpstreamFinish()
    }
    override def onUpstreamFailure(ex: Throwable): Unit = {
      setHandler(in, previous)
      previous.onUpstreamFailure(ex)
    }
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
  final protected def emitMultiple[T](out: Outlet[T], elems: Iterator[T]): Unit = emitMultiple(out, elems, DoNothing)

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

  private abstract class Emitting[T](val out: Outlet[T], val previous: OutHandler, andThen: () ⇒ Unit) extends OutHandler {
    private var followUps: Emitting[T] = _
    private var followUpsTail: Emitting[T] = _
    private def as[U] = this.asInstanceOf[Emitting[U]]

    protected def followUp(): Unit = {
      setHandler(out, previous)
      andThen()
      if (followUps != null) {
        getHandler(out) match {
          case e: Emitting[_] ⇒ e.as[T].addFollowUps(this)
          case _ ⇒
            val next = dequeue()
            if (next.isInstanceOf[EmittingCompletion[_]]) complete(out)
            else setHandler(out, next)
        }
      }
    }

    def addFollowUp(e: Emitting[T]): Unit =
      if (followUps == null) {
        followUps = e
        followUpsTail = e
      } else {
        followUpsTail.followUps = e
        followUpsTail = e
      }

    private def addFollowUps(e: Emitting[T]): Unit =
      if (followUps == null) {
        followUps = e.followUps
        followUpsTail = e.followUpsTail
      } else {
        followUpsTail.followUps = e.followUps
        followUpsTail = e.followUpsTail
      }

    /**
     * Dequeue `this` from the head of the queue, meaning that this object will
     * not be retained (setHandler will install the followUp). For this reason
     * the followUpsTail knowledge needs to be passed on to the next runner.
     */
    private def dequeue(): Emitting[T] = {
      val ret = followUps
      ret.followUpsTail = followUpsTail
      ret
    }

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

  private class EmittingCompletion[T](_out: Outlet[T], _previous: OutHandler) extends Emitting(_out, _previous, DoNothing) {
    override def onPull(): Unit = complete(out)
  }

  /**
   * Install a handler on the given inlet that emits received elements on the
   * given outlet before pulling for more data. `doFinish` and `doFail` control whether
   * completion or failure of the given inlet shall lead to stage termination or not.
   * `doPull` instructs to perform one initial pull on the `from` port.
   */
  final protected def passAlong[Out, In <: Out](from: Inlet[In], to: Outlet[Out],
                                                doFinish: Boolean = true, doFail: Boolean = true,
                                                doPull: Boolean = false): Unit = {
    class PassAlongHandler extends InHandler with (() ⇒ Unit) {
      override def apply(): Unit = tryPull(from)
      override def onPush(): Unit = {
        val elem = grab(from)
        emit(to, elem, this)
      }
      override def onUpstreamFinish(): Unit = if (doFinish) completeStage()
      override def onUpstreamFailure(ex: Throwable): Unit = if (doFail) failStage(ex)
    }
    val ph = new PassAlongHandler
    if (_interpreter != null) {
      if (isAvailable(from)) emit(to, grab(from), ph)
      if (doFinish && isClosed(from)) completeStage()
    }
    setHandler(from, ph)
    if (doPull) tryPull(from)
  }

  /**
   * Obtain a callback object that can be used asynchronously to re-enter the
   * current [[GraphStage]] with an asynchronous notification. The [[invoke()]] method of the returned
   * [[AsyncCallback]] is safe to be called from other threads and it will in the background thread-safely
   * delegate to the passed callback function. I.e. [[invoke()]] will be called by the external world and
   * the passed handler will be invoked eventually in a thread-safe way by the execution environment.
   *
   * This object can be cached and reused within the same [[GraphStageLogic]].
   */
  final protected def getAsyncCallback[T](handler: T ⇒ Unit): AsyncCallback[T] = {
    new AsyncCallback[T] {
      override def invoke(event: T): Unit =
        interpreter.onAsyncInput(GraphStageLogic.this, event, handler.asInstanceOf[Any ⇒ Unit])
    }
  }

  private var _stageActorRef: StageActorRef = _
  final def stageActorRef: ActorRef = _stageActorRef match {
    case null ⇒ throw StageActorRefNotInitializedException()
    case ref  ⇒ ref
  }

  /**
   * Initialize a [[StageActorRef]] which can be used to interact with from the outside world "as-if" an [[Actor]].
   * The messages are looped through the [[getAsyncCallback]] mechanism of [[GraphStage]] so they are safe to modify
   * internal state of this stage.
   *
   * This method must not (the earliest) be called after the [[GraphStageLogic]] constructor has finished running,
   * for example from the [[preStart]] callback the graph stage logic provides.
   *
   * Created [[StageActorRef]] to get messages and watch other actors in synchronous way.
   *
   * The [[StageActorRef]]'s lifecycle is bound to the Stage, in other words when the Stage is finished,
   * the Actor will be terminated as well. The entity backing the [[StageActorRef]] is not a real Actor,
   * but the [[GraphStageLogic]] itself, therefore it does not react to [[PoisonPill]].
   *
   * @param receive callback that will be called upon receiving of a message by this special Actor
   * @return minimal actor with watch method
   */
  // FIXME: I don't like the Pair allocation :(
  final protected def getStageActorRef(receive: ((ActorRef, Any)) ⇒ Unit): StageActorRef = {
    _stageActorRef match {
      case null ⇒
        val actorMaterializer = ActorMaterializer.downcast(interpreter.materializer)
        val provider = actorMaterializer.supervisor.asInstanceOf[InternalActorRef].provider
        val path = actorMaterializer.supervisor.path / StageActorRef.name.next()
        _stageActorRef = new StageActorRef(provider, actorMaterializer.logger, getAsyncCallback, receive, path)
        _stageActorRef
      case existing ⇒
        existing.become(receive)
        existing
    }
  }

  // Internal hooks to avoid reliance on user calling super in preStart
  /** INTERNAL API */
  protected[stream] def beforePreStart(): Unit = ()

  // Internal hooks to avoid reliance on user calling super in postStop
  /** INTERNAL API */
  protected[stream] def afterPostStop(): Unit = {
    if (_stageActorRef ne null) _stageActorRef.stop()
    _stageActorRef = null
  }

  /**
   * Invoked before any external events are processed, at the startup of the stage.
   */
  def preStart(): Unit = ()

  /**
   * Invoked after processing of external events stopped because the stage is about to stop or fail.
   */
  def postStop(): Unit = ()

  /**
   * If this method returns true when all ports had been closed then the stage is not stopped until
   * completeStage() or failStage() are explicitly called
   */
  def keepGoingAfterAllPortsClosed: Boolean = false
}

/**
 * An asynchronous callback holder that is attached to a [[GraphStageLogic]].
 * Invoking [[AsyncCallback#invoke]] will eventually lead to the registered handler
 * being called.
 */
trait AsyncCallback[T] {
  /**
   * Dispatch an asynchronous notification. This method is thread-safe and
   * may be invoked from external execution contexts.
   */
  def invoke(t: T): Unit
}

abstract class TimerGraphStageLogic(_shape: Shape) extends GraphStageLogic(_shape) {
  import TimerMessages._

  private val keyToTimers = mutable.Map[Any, Timer]()
  private val timerIdGen = Iterator from 1

  private var _timerAsyncCallback: AsyncCallback[Scheduled] = _
  private def getTimerAsyncCallback: AsyncCallback[Scheduled] = {
    if (_timerAsyncCallback eq null)
      _timerAsyncCallback = getAsyncCallback(onInternalTimer)

    _timerAsyncCallback
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
   * Will be called when the scheduled timer is triggered.
   * @param timerKey key of the scheduled timer
   */
  protected def onTimer(timerKey: Any): Unit = ()

  // Internal hooks to avoid reliance on user calling super in postStop
  protected[stream] override def afterPostStop(): Unit = {
    super.afterPostStop()
    if (keyToTimers ne null) {
      keyToTimers.foreach { case (_, Timer(_, task)) ⇒ task.cancel() }
      keyToTimers.clear()
    }
  }

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
   * Schedule timer to call [[#onTimer]] periodically with the given interval.
   * Any existing timer with the same key will automatically be canceled before
   * adding the new timer.
   */
  final protected def schedulePeriodically(timerKey: Any, interval: FiniteDuration): Unit =
    schedulePeriodicallyWithInitialDelay(timerKey, interval, interval)

}

/**
 * Collection of callbacks for an input port of a [[GraphStage]]
 */
trait InHandler {
  /**
   * Called when the input port has a new element available. The actual element can be retrieved via the
   * [[GraphStageLogic.grab()]] method.
   */
  def onPush(): Unit

  /**
   * Called when the input port is finished. After this callback no other callbacks will be called for this port.
   */
  def onUpstreamFinish(): Unit = GraphInterpreter.currentInterpreter.activeStage.completeStage()

  /**
   * Called when the input port has failed. After this callback no other callbacks will be called for this port.
   */
  def onUpstreamFailure(ex: Throwable): Unit = GraphInterpreter.currentInterpreter.activeStage.failStage(ex)
}

/**
 * Collection of callbacks for an output port of a [[GraphStage]]
 */
trait OutHandler {
  /**
   * Called when the output port has received a pull, and therefore ready to emit an element, i.e. [[GraphStageLogic.push()]]
   * is now allowed to be called on this port.
   */
  def onPull(): Unit

  /**
   * Called when the output port will no longer accept any new elements. After this callback no other callbacks will
   * be called for this port.
   */
  def onDownstreamFinish(): Unit = {
    GraphInterpreter
      .currentInterpreter
      .activeStage
      .completeStage()
  }
}

/**
 * Java API: callbacks for an input port where termination logic is predefined
 * (completing when upstream completes, failing when upstream fails).
 */
abstract class AbstractInHandler extends InHandler

/**
 * Java API: callbacks for an output port where termination logic is predefined
 * (completing when downstream cancels).
 */
abstract class AbstractOutHandler extends OutHandler

/**
 * Java API: callback combination for output and input ports where termination logic is predefined
 * (completing when upstream completes, failing when upstream fails, completing when downstream cancels).
 */
abstract class AbstractInOutHandler extends InHandler with OutHandler
