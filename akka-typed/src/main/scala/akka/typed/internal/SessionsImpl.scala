/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.{ actor ⇒ a }
import java.util.concurrent.ArrayBlockingQueue
import Sessions._
import ScalaDSL._
import scala.concurrent.duration.FiniteDuration
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.runtime.BoxedUnit
import scala.runtime.BoxesRunTime
import java.util.LinkedList

/**
 * Implementation notes:
 *
 *  - a process is a tree of AST nodes, where each leaf is a producer of a process
 *  - interpreter has a list of currently existing processes
 *  - processes may be active (i.e. waiting for external input) or passive (i.e.
 *    waiting for internal progress)
 *  - external messages and internal completions are events that are enqueued
 *  - event processing runs in FIFO order, which ensures some fairness between
 *    concurrent processes
 *  - what is stored is actually a Traversal of a tree which keeps the back-links
 *    pointing towards the root; this is cheaper than rewriting the trees
 *  - the maximum event queue size is bounded by #channels + #processes (multiple
 *    events from the same channel can be coalesced inside that channel by a counter)
 *  - this way even complex process trees can be executed with minimal allocations
 *    (fixed-size preallocated arrays for event queue and back-links, preallocated
 *    processes can even be reentrant due to separate unique Traversals)
 */
private[typed] object ProcessInterpreter {

  sealed trait TraversalState
  case object HasValue extends TraversalState
  case object NeedsTrampoline extends TraversalState
  case object NeedsExternalInput extends TraversalState
  case object NeedsInternalInput extends TraversalState
  case object NeedsInternalInputs extends TraversalState

  sealed trait Input // could be a channel or a timer
  final class Timer(delay: FiniteDuration) extends Input with Command {
    val cancelable = actorContext.schedule(delay, actorContext.self, this)
  }

  val Debug = true
}
private[typed] class ProcessInterpreter(initial: ⇒ Process[Any]) extends Behavior[Command] {
  import ProcessInterpreter._

  // FIXME data structures to be optimized
  private var _internalTriggers = Map.empty[Traversal, Traversal]
  private var _externalTriggers = Map.empty[Input, AnyRef]
  private var _channels = Map.empty[ActorRef[_], ChannelImpl[_]]
  private val queue = new LinkedList[Traversal]

  private def internalTrigger(src: Traversal, dst: Traversal): Unit = {
    _internalTriggers += src → dst
  }
  private def externalTrigger(src: Input, dst: Traversal): Unit = {
    src match {
      case c: ChannelImpl[_] ⇒ _channels += c.ref → c
      case _                 ⇒
    }
    _externalTriggers.get(src) match {
      case None ⇒ _externalTriggers += src → dst
      case Some(t: Traversal) ⇒
        val list = new LinkedList[Traversal]
        list.add(t)
        list.add(dst)
        _externalTriggers += src → list
      case Some(l: LinkedList[Traversal @unchecked]) ⇒ l.add(dst)
      case _                                         ⇒ throw new AssertionError
    }
  }
  private def getExternalTrigger(src: Input): Traversal =
    _externalTriggers.get(src) match {
      case None ⇒ null
      case Some(t: Traversal) ⇒
        _externalTriggers -= src
        t
      case Some(l: LinkedList[Traversal @unchecked]) ⇒
        val t = l.poll()
        if (l.isEmpty()) _externalTriggers -= src
        t
      case _ ⇒ throw new AssertionError
    }
  private def removeExternalTrigger(input: Input, traversal: Traversal): Unit =
    _externalTriggers.get(input) match {
      case None               ⇒ // nothing to do
      case Some(t: Traversal) ⇒ if (t == traversal) _externalTriggers -= input
      case Some(l: LinkedList[Traversal @unchecked]) ⇒
        l.remove(traversal)
        if (l.isEmpty()) _externalTriggers -= input
      case _ ⇒ throw new AssertionError
    }
  private def cancelChannel(c: ChannelImpl[_]): Unit =
    _externalTriggers.get(c) match {
      case None ⇒ // nothing to do
      case Some(t: Traversal) ⇒
        t.cancel()
        _externalTriggers -= c
      case Some(l: LinkedList[Traversal @unchecked]) ⇒
        val iter = l.iterator()
        while (iter.hasNext) iter.next().cancel()
        _externalTriggers -= c
      case _ ⇒ throw new AssertionError
    }

  def management(ctx: ActorContext[Sessions.Command], msg: Signal): Behavior[Sessions.Command] = {
    val holder = ProcessImpl.contextHolder.get()
    val old = holder(0)
    holder(0) = ctx
    try {
      msg match {
        case PreStart ⇒
          new Traversal(initial.sorry)
          execute()
        case PostStop ⇒
          // FIXME clean everything up
          Same
        case Terminated(ref) if _channels.contains(ref) ⇒
          val channel = _channels(ref)
          _channels -= ref
          channel.seal()
          cancelChannel(channel)
          execute()
        case _ ⇒ Same
      }
    } finally holder(0) = old
  }

  def message(ctx: ActorContext[Sessions.Command], msg: Sessions.Command): Behavior[Sessions.Command] = {
    val holder = ProcessImpl.contextHolder.get()
    val old = holder(0)
    holder(0) = ctx
    try {
      msg match {
        case c: ChannelImpl[_] ⇒
          if (c.isAlive) {
            c.registerReceipt()
            getExternalTrigger(c) match {
              case null ⇒
              case t ⇒
                t.dispatchInput(c.receiveOne(), c)
                triggerCompletions(t)
            }
          }
          execute()
        case timer: Timer ⇒
          getExternalTrigger(timer) match {
            case null ⇒
            case t ⇒
              t.dispatchInput((), timer)
              triggerCompletions(t)
          }
          execute()
      }
    } finally holder(0) = old
  }

  private def execute(): Behavior[Command] = {
    while (!queue.isEmpty()) {
      val traversal = queue.poll()
      if (Debug) println(s"$self running $traversal")
      if (traversal.state == NeedsTrampoline) traversal.dispatchTrampoline()
      triggerCompletions(traversal)
    }
    if (_internalTriggers.isEmpty && _externalTriggers.isEmpty) Stopped else Same
  }

  @tailrec private def triggerCompletions(traversal: Traversal): Unit =
    if (traversal.state == HasValue) {
      if (Debug) println(s"$self finished $traversal")
      _internalTriggers.get(traversal) match {
        case None ⇒ // nobody listening
        case Some(t) ⇒
          _internalTriggers -= traversal
          t.dispatchInput(traversal.getValue, traversal)
          triggerCompletions(t)
      }
    }

  private class Traversal(process: ProcessImpl[Any]) {

    if (Debug) println(s"$self new traversal for $process")

    override def toString: String = if (Debug) s"Traversal($process, $state, ${stack.toList}, $ptr)" else super.toString

    /*
     * The state defines what is on the stack:
     *  - HasValue means stack only contains the single end result
     *  - NeedsTrampoline: pop value, then pop operation that needs it
     *  - NeedsExternalInput: pop valueOrInput, then pop operation
     *  - NeedsInternalInput: pop valueOrTraversal, then pop operation
     *  - NeedsInternalInputs: pop valueOrTraversal twice, then pop operation
     */
    private val stack = new Array[AnyRef](process.depth)
    private var ptr = 0
    private var _state: TraversalState = initialize(process)

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
    private def valueOrTrampoline() =
      if (ptr == 1) HasValue
      else {
        queue.add(this)
        NeedsTrampoline
      }

    private def triggerOn(i: Input): i.type = {
      externalTrigger(i, this)
      i
    }

    def getValue: Any = {
      assert(_state == HasValue)
      stack(0)
    }

    /**
     * Obtain the current state for this Traversal.
     */
    def state: TraversalState = _state

    @tailrec private def initialize(node: ProcessImpl[Any]): TraversalState =
      node match {
        case Value(v) ⇒
          push(v)
          valueOrTrampoline()
        case FlatMap(first, _) ⇒
          push(node)
          initialize(first)
        case Fork(other) ⇒
          new Traversal(other)
          push(())
          valueOrTrampoline()
        case Join(left, right) ⇒
          val l = new Traversal(left)
          val r = new Traversal(right)
          if (l.state == HasValue) {
            if (r.state == HasValue) {
              push(l.getValue → r.getValue)
              valueOrTrampoline()
            } else {
              push(node)
              push(l.getValue)
              push(r)
              internalTrigger(r, this)
              NeedsInternalInputs
            }
          } else {
            push(node)
            push(l)
            internalTrigger(l, this)
            if (r.state == HasValue) {
              push(r.getValue)
            } else {
              push(r)
              internalTrigger(r, this)
            }
            NeedsInternalInputs
          }
        case Race(left, right) ⇒
          val l = new Traversal(left)
          val r = new Traversal(right)
          if (l.state == HasValue) {
            r.cancel()
            push(l.getValue)
            valueOrTrampoline()
          } else if (r.state == HasValue) {
            l.cancel()
            push(r.getValue)
            valueOrTrampoline()
          } else {
            push(node)
            push(l)
            internalTrigger(l, this)
            push(r)
            internalTrigger(r, this)
            NeedsInternalInputs
          }
        case Trap(proc, _) ⇒
          push(node)
          initialize(proc)
        case Read(channel, seal) ⇒
          if (channel.canReceive) {
            push(channel.receiveOne())
            if (seal) channel.seal()
            valueOrTrampoline()
          } else if (channel.isAlive) {
            push(node)
            push(triggerOn(channel))
            NeedsExternalInput
          } else {
            push(FailedImpl.value)
            valueOrTrampoline()
          }
        case Schedule(delay, _) ⇒
          push(node)
          push(triggerOn(new Timer(delay)))
          NeedsExternalInput
        case t: TerminationImpl ⇒
          push(t.value)
          valueOrTrampoline()
      }

    def dispatchInput(value: Any, source: Traversal): Unit = {
      if (Debug) println(s"$self dispatching input $value from $source to $this")
      _state match {
        case NeedsInternalInput ⇒
          assert(source eq pop())
          push(value)
          _state = valueOrTrampoline()
        case NeedsInternalInputs ⇒
          val other =
            if (source eq stack(ptr - 1)) {
              stack(ptr - 1) = value.asInstanceOf[AnyRef]
              stack(ptr - 2)
            } else if (source eq stack(ptr - 2)) {
              stack(ptr - 2) = value.asInstanceOf[AnyRef]
              stack(ptr - 1)
            } else throw new AssertionError
          stack(ptr - 3) match {
            case Join(_, _) ⇒
              if (!other.isInstanceOf[Traversal]) {
                val right = pop()
                val left = pop()
                pop() // discard the Join node
                push(left → right)
                _state = valueOrTrampoline()
              }
            case Race(_, _) ⇒
              val t = other.asInstanceOf[Traversal]
              t.cancel()
              _internalTriggers -= t
              pop() // discard right
              pop() // discard left
              pop() // discard Race node
              push(value)
              _state = valueOrTrampoline()
          }
        case _ ⇒ throw new AssertionError
      }
    }

    def dispatchInput(value: Any, source: Input): Unit = {
      if (Debug) println(s"$self dispatching input $value from $source to $this")
      assert(_state == NeedsExternalInput)
      assert(source eq pop())
      pop() match {
        case Read(channel, seal) ⇒
          if (seal) channel.seal()
          push(value)
          _state = valueOrTrampoline()
        case Schedule(_, v) ⇒
          _state = initialize(v)
      }
    }

    def dispatchTrampoline(): Unit = {
      assert(_state == NeedsTrampoline)
      pop() match {
        case s: TerminationImpl.Sentinel ⇒
          // roll back the stack
          while (ptr > 0)
            pop() match {
              case Trap(_, cleanup) ⇒ try cleanup(s.back) catch { case NonFatal(_) ⇒ }
              case _                ⇒ // nothing to do
            }
          push(s)
          _state = HasValue
        case value ⇒
          pop() match {
            case FlatMap(_, cont) ⇒
              try {
                // FIXME inline on this stack if there is enough space
                val t = new Traversal(cont(value).sorry)
                if (t.state == HasValue) {
                  push(t.getValue)
                  _state = valueOrTrampoline()
                } else {
                  push(t)
                  internalTrigger(t, this)
                  _state = NeedsInternalInput
                }
              } catch {
                case NonFatal(_) ⇒
                  push(FailedImpl.value)
                  _state = valueOrTrampoline()
              }
            case Trap(_, cleanup) ⇒
              try cleanup(Halted) catch { case NonFatal(_) ⇒ }
              push(value)
              _state = valueOrTrampoline()
          }
      }
    }

    def cancel(): Unit = {
      if (Debug) println(s"$self canceling $this")
      _state match {
        case HasValue        ⇒ // nothing to do
        case NeedsTrampoline ⇒ stack(ptr - 1) = CanceledImpl.value
        case NeedsExternalInput ⇒
          pop() match {
            case channel: ChannelImpl[_] ⇒
              removeExternalTrigger(channel, this)
            case t: Timer ⇒
              t.cancelable.cancel()
              getExternalTrigger(t)
          }
          pop() match {
            case Read(channel, true) ⇒ channel.seal()
            case _                   ⇒
          }
          push(CanceledImpl.value)
          _state = valueOrTrampoline()
        case NeedsInternalInput ⇒
          pop().asInstanceOf[Traversal].cancel()
          push(CanceledImpl.value)
          _state = valueOrTrampoline()
        case NeedsInternalInputs ⇒
          pop() match { case t: Traversal ⇒ t.cancel() }
          pop() match { case t: Traversal ⇒ t.cancel() }
          pop() // Join or Race
          push(CanceledImpl.value)
          _state = valueOrTrampoline()
      }
    }
  }

  private def self = actorContext.self
}

private[typed] sealed abstract class ProcessImpl[+T] extends Process[T] {
  def flatMap[U](f: T ⇒ Process[U]): Process[U] = FlatMap(this, f)
  def join[U](other: Process[U]): Process[(T, U)] = Join(this, other.sorry)
  def race[U >: T](other: Process[U]): Process[U] = Race(this, other.sorry)
  def depth: Int
}

private[typed] object ProcessImpl {
  val contextHolder = new ThreadLocal[Array[AnyRef]] {
    // avoid referencing any Akka classes in here, otherwise ClassLoader leaks
    override def initialValue() = new Array[AnyRef](1)
  }
}

private[typed] final case class Value[T](t: T) extends ProcessImpl[T] { def depth = 1 }
private[typed] final case class FlatMap[T, U](first: ProcessImpl[T], second: T ⇒ Process[U]) extends ProcessImpl[U] { def depth = first.depth + 1 }
private[typed] final case class Fork(background: ProcessImpl[Any]) extends ProcessImpl[Unit] { def depth = 1 }
private[typed] final case class Join[T, U](first: ProcessImpl[T], second: ProcessImpl[U]) extends ProcessImpl[(T, U)] { def depth = 3 }
private[typed] final case class Race[T](first: ProcessImpl[T], second: ProcessImpl[T]) extends ProcessImpl[T] { def depth = 3 }
private[typed] final case class Trap[T](process: ProcessImpl[T], cleanup: Termination ⇒ Unit) extends ProcessImpl[T] { def depth = process.depth + 1 }
private[typed] final case class Read[T](channel: ChannelImpl[T], sealAfterwards: Boolean) extends ProcessImpl[T] { def depth = 2 }
private[typed] final case class Schedule[T](delay: FiniteDuration, then: ProcessImpl[T]) extends ProcessImpl[T] { def depth = Math.max(2, then.depth) }

private[typed] sealed abstract class TerminationImpl(val signal: Termination) extends ProcessImpl[Nothing] {
  override def flatMap[U](f: Nothing ⇒ Process[U]): Process[U] = this
  override def join[U](other: Process[U]): Process[(Nothing, U)] = this
  override def race[U](other: Process[U]): Process[U] = this
  override def depth = 1
  // we need a private value that is distinct from all user values
  val value = new TerminationImpl.Sentinel(signal)
}
private[typed] object TerminationImpl {
  class Sentinel(val back: Termination)
}
private[typed] case object HaltedImpl extends TerminationImpl(Halted)
private[typed] case object CanceledImpl extends TerminationImpl(Canceled)
private[typed] case object FailedImpl extends TerminationImpl(Failed)

private[typed] final class ChannelImpl[T](_capacity: Int, _ctx: ActorContext[Sessions.Command])
  extends Channel[T] with Command with ProcessInterpreter.Input with Function1[T, Sessions.Command] {

  private val queue = new ArrayBlockingQueue[T](_capacity) // FIXME replace with lock-free queue
  private var toRead = 0

  val parent = _ctx.self

  def registerReceipt(): Unit = toRead += 1
  def canReceive: Boolean = toRead > 0
  def receiveOne(): T = queue.poll()
  def isAlive: Boolean = toRead >= 0

  def apply(msg: T): Sessions.Command =
    if (queue.offer(msg)) this
    else null // adapter drops nulls

  override val ref: ActorRef[T] = _ctx.watch(_ctx.spawnAdapter(this))

  def seal(): Unit = {
    if (isAlive) ref.sorry.sendSystem(Terminate())
    toRead = -1
  }
}
