/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.{ actor ⇒ a }
import java.util.concurrent.ArrayBlockingQueue
import ScalaProcess._
import ScalaDSL._
import scala.concurrent.duration._
import scala.annotation.tailrec
import scala.util.control.NonFatal
import java.util.LinkedList
import scala.collection.immutable.TreeSet
import akka.actor.Cancellable
import java.util.concurrent.TimeoutException
import akka.util.TypedMultiMap
import akka.Done
import akka.event.Logging

/**
 * Implementation notes:
 *
 *  - a process is a tree of AST nodes, where each leaf is a producer of a process
 *    (in fact the inner nodes are all FlatMap)
 *  - interpreter has a list of currently existing processes
 *  - processes may be active (i.e. waiting for external input) or passive (i.e.
 *    waiting for internal progress)
 *  - external messages and internal completions are events that are enqueued
 *  - event processing runs in FIFO order, which ensures some fairness between
 *    concurrent processes
 *  - what is stored is actually a Traversal of a tree which keeps the back-links
 *    pointing towards the root; this is cheaper than rewriting the trees
 *  - the maximum event queue size is bounded by #processes (multiple
 *    events from the same channel can be coalesced inside that channel by a counter)
 *  - this way even complex process trees can be executed with minimal allocations
 *    (fixed-size preallocated arrays for event queue and back-links, preallocated
 *    processes can even be reentrant due to separate unique Traversals)
 *
 * TODO:
 *   enable noticing when watchee failed
 */
private[typed] object ProcessInterpreter {

  sealed trait TraversalState
  case object HasValue extends TraversalState
  case object NeedsTrampoline extends TraversalState
  case object NeedsExternalInput extends TraversalState
  case object NeedsInternalInput extends TraversalState

  // used when ShortCircuit stops the process
  case object NoValue

  final case class RunCleanup(cleanup: () ⇒ Unit)

  val Debug = false

  /*
   * The normal ordering provides a transitive order that does not cope well
   * with wrap-arounds. This ordering is only transitive if all values to be
   * sorted are within a 290 year interval, but then it does the right thing
   * regardless of where a wrap-around occurs.
   */
  implicit val timeoutOrdering: Ordering[Deadline] =
    new Ordering[Deadline] {
      override def compare(a: Deadline, b: Deadline): Int = {
        val diff = a.time.toNanos - b.time.toNanos
        if (diff > 0) 1 else if (diff < 0) -1 else 0
      }
    }
  val emptyTimeouts = TreeSet.empty[Deadline]
  val notScheduled: Cancellable = new Cancellable {
    override def cancel(): Boolean = false
    override def isCancelled: Boolean = true
  }

  val wrapReturn = (o: Any) ⇒ Return(o).asInstanceOf[Operation[Any, Any]]

  case class Timeout(deadline: Deadline) extends InternalActorCmd[Nothing]
}

private[typed] class ProcessInterpreter[T](initial: ⇒ Process[T, Any]) extends Behavior[ActorCmd[T]] {
  import ProcessInterpreter._

  // FIXME data structures to be optimized
  private var internalTriggers = Map.empty[Traversal[_], Traversal[_]]
  private val queue = new LinkedList[Traversal[_]]
  private var processRoots = Set.empty[Traversal[_]]
  private var mainProcess: Traversal[T] = _
  private var timeouts = emptyTimeouts
  private var timeoutTask = notScheduled
  private var watchMap = Map.empty[ActorRef[Nothing], Set[AbstractWatchRef]]
  private var stateMap = Map.empty[StateKey[_], Any]

  def management(ctx: ActorContext[ActorCmd[T]], msg: Signal): Behavior[ActorCmd[T]] = {
    msg match {
      case PreStart ⇒
        mainProcess = new Traversal(initial, ctx)
        if (mainProcess.state == HasValue) triggerCompletions(ctx, mainProcess)
        else processRoots += mainProcess
        execute(ctx)
      case PostStop ⇒
        processRoots.foreach(_.cancel())
        Same
      case t @ Terminated(ref) ⇒
        watchMap.get(ref) match {
          case None ⇒ Unhandled
          case Some(set) ⇒
            if (t.failure == null) set.foreach { case w: WatchRef[tpe] ⇒ w.target ! w.msg }
            else set.foreach { case w: WatchRef[tpe] ⇒ w.target ! w.onFailure(t.failure).getOrElse(w.msg) }
            watchMap -= ref
            Same
        }
      case _ ⇒ Same
    }
  }

  def message(ctx: ActorContext[ActorCmd[T]], msg: ActorCmd[T]): Behavior[ActorCmd[T]] = {
    // for paranoia: if Timeout message is lost due to bounded mailbox (costs 50ns if nonEmpty)
    if (timeouts.nonEmpty && Deadline.now.time.toNanos - timeouts.head.time.toNanos >= 0)
      throw new TimeoutException("process timeout expired")

    msg match {
      case t: Traversal[_] ⇒
        if (Debug) println(s"${ctx.self} got message for $t")
        if (t.isAlive) {
          t.registerReceipt()
          if (t.state == NeedsExternalInput) {
            t.dispatchInput(t.receiveOne(), t)
            triggerCompletions(ctx, t)
          }
        }
        execute(ctx)
      case Timeout(_) ⇒
        // won’t get here anyway due to the clock check above, but is included for documentation
        Same
      case MainCmd(cmd) ⇒
        mainProcess.ref ! cmd
        Same
      case _ ⇒ Unhandled
    }
  }

  /**
   * Consume the queue of outstanding triggers.
   */
  private def execute(ctx: ActorContext[ActorCmd[T]]): Behavior[ActorCmd[T]] = {
    while (!queue.isEmpty()) {
      val traversal = queue.poll()
      if (traversal.state == NeedsTrampoline) traversal.dispatchTrampoline()
      triggerCompletions(ctx, traversal)
    }
    if (Debug) {
      val roots = processRoots.map(t ⇒ s"${t.process.name}(${t.ref.path.name})")
      val refs = ctx.children.map(_.path.name)
      println(s"${ctx.self} execute run finished, roots = $roots, children = $refs, timeouts = $timeouts, watchMap = $watchMap")
    }
    if (processRoots.isEmpty) Stopped else Same
  }

  /**
   * This only notifies potential listeners of the computed value of a finished
   * process; the process must clean itself up beforehand.
   */
  @tailrec private def triggerCompletions(ctx: ActorContext[ActorCmd[T]], traversal: Traversal[_]): Unit =
    if (traversal.state == HasValue) {
      if (Debug) println(s"${ctx.self} finished $traversal")
      internalTriggers.get(traversal) match {
        case None ⇒ // nobody listening
        case Some(t) ⇒
          internalTriggers -= traversal
          t.dispatchInput(traversal.getValue, traversal)
          triggerCompletions(ctx, t)
      }
    }

  def addTimeout(ctx: ActorContext[ActorCmd[T]], f: FiniteDuration): Deadline = {
    var d = Deadline.now + f
    while (timeouts contains d) d += 1.nanosecond
    if (Debug) println(s"${ctx.self} adding $d")
    if (timeouts.isEmpty || timeouts.head > d) scheduleTimeout(ctx, d)
    timeouts += d
    d
  }

  def removeTimeout(ctx: ActorContext[ActorCmd[T]], d: Deadline): Unit = {
    if (Debug) println(s"${ctx.self} removing $d")
    timeouts -= d
    if (timeouts.isEmpty) {
      timeoutTask.cancel()
      timeoutTask = notScheduled
    } else {
      val head = timeouts.head
      if (head > d) scheduleTimeout(ctx, head)
    }
  }

  def scheduleTimeout(ctx: ActorContext[ActorCmd[T]], d: Deadline): Unit = {
    if (Debug) println(s"${ctx.self} scheduling $d")
    timeoutTask.cancel()
    timeoutTask = ctx.schedule(d.timeLeft, ctx.self, Timeout(d))
  }

  def watch(ctx: ActorContext[ActorCmd[T]], w: WatchRef[_]): Cancellable = {
    val watchee = w.watchee
    val set: Set[AbstractWatchRef] = watchMap.get(watchee) match {
      case None ⇒
        ctx.watch[Nothing](watchee)
        Set(w)
      case Some(s) ⇒ s + w
    }
    watchMap = watchMap.updated(watchee, set)
    new Cancellable {
      def cancel(): Boolean = {
        watchMap.get(watchee) match {
          case None ⇒ false
          case Some(s) ⇒
            if (s.contains(w)) {
              val next = s - w
              if (next.isEmpty) {
                watchMap -= watchee
                ctx.unwatch[Nothing](watchee)
              } else {
                watchMap = watchMap.updated(watchee, next)
              }
              true
            } else false
        }
      }
      def isCancelled: Boolean = {
        watchMap.get(watchee).forall(!_.contains(w))
      }
    }
  }

  def getState[KT](key: StateKey[KT]): KT = {
    stateMap.get(key) match {
      case None    ⇒ key.initial
      case Some(v) ⇒ v.asInstanceOf[KT]
    }
  }

  def setState[KT](key: StateKey[KT], value: KT): Unit = {
    stateMap = stateMap.updated(key, value)
  }

  private class Traversal[Tself](val process: Process[Tself, Any], ctx: ActorContext[ActorCmd[T]])
    extends InternalActorCmd[Nothing] with Function1[Tself, ActorCmd[T]]
    with SubActor[Tself] {

    val deadline = process.timeout match {
      case f: FiniteDuration ⇒ addTimeout(ctx, f)
      case _                 ⇒ null
    }

    /*
     * Implementation of the queue aspect and InternalActorCmd as well as for spawnAdapter
     */

    private val mailQueue = new ArrayBlockingQueue[Tself](process.mailboxCapacity) // FIXME replace with lock-free queue
    private var toRead = 0

    val parent = ctx.self

    def registerReceipt(): Unit = toRead += 1
    def canReceive: Boolean = toRead > 0
    def receiveOne(): Tself = {
      toRead -= 1
      mailQueue.poll()
    }
    def isAlive: Boolean = toRead >= 0

    def apply(msg: Tself): ActorCmd[T] =
      if (mailQueue.offer(msg)) {
        if (Debug) println(s"$ref accepting message $msg")
        this
      } else {
        if (Debug) println(s"$ref dropping message $msg")
        null // adapter drops nulls
      }

    override val ref: ActorRef[Tself] = ctx.spawnAdapter(this, process.name)

    /*
     * Implementation of traversal logic
     */

    if (Debug) println(s"${ctx.self} new traversal for $process")

    override def toString: String =
      if (Debug) {
        val stackList = stack.toList.map {
          case null            ⇒ ""
          case t: Traversal[_] ⇒ "Traversal"
          case FlatMap(_, _)   ⇒ "FlatMap"
          case other           ⇒ other.toString
        }
        s"Traversal(${ref.path.name}, ${process.name}, $state, $stackList, $ptr)"
      } else super.toString

    @tailrec private def depth(op: Operation[_, Any], d: Int = 0): Int =
      op match {
        case FlatMap(next, _)               ⇒ depth(next, d + 1)
        case Read | Call(_, _) | Cleanup(_) ⇒ d + 2
        case _                              ⇒ d + 1
      }

    /*
     * The state defines what is on the stack:
     *  - HasValue means stack only contains the single end result
     *  - NeedsTrampoline: pop value, then pop operation that needs it
     *  - NeedsExternalInput: pop valueOrInput, then pop operation
     *  - NeedsInternalInput: pop valueOrTraversal, then pop operation
     */
    private var stack = new Array[AnyRef](5)
    private var ptr = 0
    private var _state: TraversalState = initialize(process.operation)

    private def push(v: Any): Unit = {
      stack(ptr) = v.asInstanceOf[AnyRef]
      ptr += 1
    }
    private def pop(): AnyRef = {
      ptr -= 1
      val ret = stack(ptr)
      stack(ptr) = null
      ret
    }
    private def peek(): AnyRef =
      if (ptr == 0) null else stack(ptr - 1)
    private def ensureSpace(n: Int): Unit =
      if (stack.length - ptr < n) {
        val larger = new Array[AnyRef](n + ptr)
        java.lang.System.arraycopy(stack, 0, larger, 0, ptr)
        stack = larger
      }

    private def valueOrTrampoline() =
      if (ptr == 1) {
        shutdown()
        HasValue
      } else if (peek() == NoValue) {
        runCleanups()
        push(NoValue)
        shutdown()
        HasValue
      } else {
        queue.add(this)
        NeedsTrampoline
      }

    private def triggerOn(t: Traversal[_]): t.type = {
      internalTriggers += (t → this)
      t
    }

    def getValue: Any = {
      assert(_state == HasValue)
      stack(0)
    }

    /**
     * Obtain the current state for this Traversal.
     */
    def state: TraversalState = _state

    private def initialize(node: Operation[_, Any]): TraversalState = {
      @tailrec def rec(node: Operation[_, Any]): TraversalState =
        node match {
          case FlatMap(first, _) ⇒
            push(node)
            rec(first)
          case ShortCircuit ⇒
            push(NoValue)
            valueOrTrampoline()
          case System ⇒
            push(ctx.system)
            valueOrTrampoline()
          case Read ⇒
            if (canReceive) {
              push(receiveOne())
              valueOrTrampoline()
            } else {
              push(node)
              push(this)
              NeedsExternalInput
            }
          case ProcessSelf ⇒
            push(ref)
            valueOrTrampoline()
          case ActorSelf ⇒
            push(ctx.self)
            valueOrTrampoline()
          case Return(value) ⇒
            push(value)
            valueOrTrampoline()
          case Call(process, _) ⇒
            push(node)
            push(triggerOn(new Traversal(process, ctx)))
            NeedsInternalInput
          case Fork(other) ⇒
            val t = new Traversal(other, ctx)
            processRoots += t
            push(t)
            valueOrTrampoline()
          case Spawn(proc @ Process(name, timeout, mailboxCapacity, ops), deployment) ⇒
            val ref =
              if (name == "") ctx.spawnAnonymous(proc.toBehavior, deployment)
              else ctx.spawn(proc.toBehavior, name, deployment)
            push(ref)
            valueOrTrampoline()
          case Schedule(delay, msg, target) ⇒
            push(ctx.schedule(delay, target, msg))
            valueOrTrampoline()
          case w: WatchRef[_] ⇒
            push(watch(ctx, w))
            valueOrTrampoline()
          case state: State[s, k, ev, ex] ⇒
            val current = getState(state.key)
            val (events, read) = state.transform(current)
            val next = events.foldLeft(current)(state.key.apply(_, _))
            setState(state.key, next)
            push(read)
            valueOrTrampoline()
          case state: StateR[s, k, ev] ⇒
            val current = getState(state.key)
            val events = state.transform(current)
            val next = events.foldLeft(current)(state.key.apply(_, _))
            setState(state.key, next)
            push(next)
            valueOrTrampoline()
          case Forget(key) ⇒
            stateMap -= key
            push(Done)
            valueOrTrampoline()
          case Cleanup(cleanup) ⇒
            val f @ FlatMap(_, _) = pop() // this is ensured at the end of initialize()
            push(RunCleanup(cleanup))
            push(f)
            push(Done)
            valueOrTrampoline()
        }

      node match {
        case _: Cleanup ⇒
          /*
           * a naked Cleanup cannot be pushed because it expects a FlatMap
           * beneath itself; making rec() robust against that would lead to
           * erroneous execution order for the actual cleanup when stack is
           * not empty right now
           */
          ensureSpace(depth(node) + 1)
          rec(FlatMap(node.asInstanceOf[Operation[Any, Any]], wrapReturn))
        case _ ⇒
          ensureSpace(depth(node))
          rec(node)
      }
    }

    def dispatchInput(value: Any, source: Traversal[_]): Unit = {
      if (Debug) println(s"${ctx.self} dispatching input $value from ${source.process.name} to $this")
      _state match {
        case NeedsInternalInput ⇒
          assert(source eq pop())
          val Call(proc, replacement) = pop()
          assert(source.process eq proc)
          if (value != NoValue) push(value) else push(replacement.getOrElse(NoValue))
          _state = valueOrTrampoline()
        case NeedsExternalInput ⇒
          assert(this eq pop())
          assert(Read eq pop())
          push(value)
          _state = valueOrTrampoline()
        case _ ⇒ throw new AssertionError
      }
    }

    def dispatchTrampoline(): Unit = {
      if (Debug) println(s"${ctx.self} dispatching trampoline for $this")
      assert(_state == NeedsTrampoline)
      val value = pop()
      pop() match {
        case FlatMap(_, cont) ⇒
          val contOps = cont(value)
          if (Debug) println(s"${ctx.self} flatMap yielded $contOps")
          _state = initialize(contOps)
        case RunCleanup(cleanup) ⇒
          cleanup()
          push(value)
          _state = valueOrTrampoline()
      }
    }

    def cancel(): Unit = {
      if (Debug) println(s"${ctx.self} canceling $this")
      @tailrec def rec(t: Traversal[_], acc: List[RunCleanup]): List[RunCleanup] =
        if (t.isAlive) {
          t.shutdown()
          t._state match {
            case HasValue           ⇒ acc
            case NeedsTrampoline    ⇒ addCleanups(t, acc)
            case NeedsExternalInput ⇒ addCleanups(t, acc)
            case NeedsInternalInput ⇒
              val next = pop().asInstanceOf[Traversal[_]]
              internalTriggers -= next
              rec(next, addCleanups(t, acc))
          }
        } else Nil
      @tailrec def addCleanups(t: Traversal[_], acc: List[RunCleanup], idx: Int = 0): List[RunCleanup] =
        if (idx < t.ptr) {
          t.stack(idx) match {
            case r: RunCleanup ⇒ addCleanups(t, r :: acc, idx + 1)
            case _             ⇒ addCleanups(t, acc, idx + 1)
          }
        } else acc

      // run cleanup actions in reverse order, catching exceptions
      rec(this, Nil) foreach { run ⇒
        if (Debug) println(s"${ctx.self} running cleanup action")
        try run.cleanup()
        catch {
          case NonFatal(ex) ⇒
            ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, ProcessInterpreter.this.getClass,
              s"exception in cleanup handler while canceling process ${process.name}: ${ex.getMessage}"))
        }
      }
    }

    /*
     * This method is used when voluntarily giving up, hence failure should
     * fail the Actor (which will in turn run all remaining cleanups that are
     * still on the stack).
     */
    private def runCleanups(): Unit = {
      while (ptr > 0) {
        pop() match {
          case RunCleanup(cleanup) ⇒ cleanup()
          case _                   ⇒
        }
      }
    }

    private def shutdown(): Unit = {
      ref.sorry.sendSystem(Terminate())
      toRead = -1
      processRoots -= this
      if (deadline != null) removeTimeout(ctx, deadline)
    }

  }

}
