/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import scala.annotation.tailrec
import scala.util.control.NonFatal

// TODO:
// fix jumpback table with keep-going-on-complete ops (we might jump between otherwise isolated execution regions)
// implement grouped, buffer
// add recover

trait Op[In, Out, PushD <: Directive, PullD <: Directive, Ctxt <: Context[Out]] {
  private[fusing] var holding = false
  private[fusing] var allowedToPush = false
  private[fusing] var terminationPending = false

  def isHolding: Boolean = holding
  def isFinishing: Boolean = terminationPending
  def onPush(elem: In, ctxt: Ctxt): PushD
  def onPull(ctxt: Ctxt): PullD
  def onUpstreamFinish(ctxt: Ctxt): Directive = ctxt.finish()
  def onDownstreamFinish(ctxt: Ctxt): Directive = ctxt.finish()
  def onFailure(cause: Throwable, ctxt: Ctxt): Directive = ctxt.fail(cause)
}

trait DeterministicOp[In, Out] extends Op[In, Out, Directive, Directive, Context[Out]]
trait DetachedOp[In, Out] extends Op[In, Out, UpstreamDirective, DownstreamDirective, DetachedContext[Out]]
trait BoundaryOp extends Op[Any, Any, Directive, Directive, BoundaryContext] {
  private[fusing] var bctxt: BoundaryContext = _
  def enter(): BoundaryContext = bctxt
}

trait TransitivePullOp[In, Out] extends DeterministicOp[In, Out] {
  final override def onPull(ctxt: Context[Out]): Directive = ctxt.pull()
}

sealed trait Directive
sealed trait UpstreamDirective extends Directive
sealed trait DownstreamDirective extends Directive
sealed trait TerminationDirective extends Directive
final class FreeDirective extends UpstreamDirective with DownstreamDirective with TerminationDirective

sealed trait Context[Out] {
  def push(elem: Out): DownstreamDirective
  def pull(): UpstreamDirective
  def finish(): FreeDirective
  def pushAndFinish(elem: Out): DownstreamDirective
  def fail(cause: Throwable): FreeDirective
  def absorbTermination(): TerminationDirective
}

trait DetachedContext[Out] extends Context[Out] {
  def hold(): FreeDirective
  def pushAndPull(elem: Out): FreeDirective
}

trait BoundaryContext extends Context[Any] {
  def exit(): FreeDirective
}

object OneBoundedInterpreter {
  final val PhantomDirective = null

  /**
   * INTERNAL API
   *
   * This artificial op is used as a boundary to prevent two forked paths of execution (complete, cancel) to cross
   * paths again. When finishing an op this op is injected in its place to isolate upstream and downstream execution
   * domains.
   */
  private[akka] object Finished extends BoundaryOp {
    override def onPush(elem: Any, ctxt: BoundaryContext): UpstreamDirective = ctxt.finish()
    override def onPull(ctxt: BoundaryContext): DownstreamDirective = ctxt.finish()
    override def onUpstreamFinish(ctxt: BoundaryContext): Directive = ctxt.exit()
    override def onDownstreamFinish(ctxt: BoundaryContext): Directive = ctxt.exit()
    override def onFailure(cause: Throwable, ctxt: BoundaryContext): Directive = ctxt.exit()
  }
}

/**
 * One-bounded interpreter for a linear chain of stream operations (graph support is possible and will be implemented
 * later)
 *
 * The ideas in this interpreter are an amalgamation of earlier ideas, notably:
 *  - The original effect-tracking implementation by Johannes Rudolph -- the difference here that effects are not chained
 *  together as classes but the callstack is used instead and only certain combinations are allowed.
 *  - The on-stack reentrant implementation by Mathias Doenitz -- the difference here that reentrancy is handled by the
 *  interpreter itself, not user code, and the interpreter is able to use the heap when needed instead of the
 *  callstack.
 *  - The pinball interpreter by Endre Sándor Varga -- the difference here that the restricition for "one ball" is
 *  lifted by using isolated execution regions, completion handling is introduced and communication with the external
 *  world is done via boundary ops.
 *
 * The design goals/features of this interpreter are:
 *  - bounded callstack and heapless execution whenever possible
 *  - callstack usage should be constant for the most common ops independently of the size of the op-chain
 *  - allocation-free execution on the hot paths
 *  - enforced backpressure-safety (boundedness) on user defined ops at compile-time (and runtime in a few cases)
 *
 * The main driving idea of this interpreter is the concept of 1-bounded execution of well-formed free choice Petri
 * nets (J. Desel and J. Esparza: Free Choice Petri Nets - https://www7.in.tum.de/~esparza/bookfc.html). Technically
 * different kinds of operations partition the chain of ops into regions where *exactly one* event is active all the
 * time. This "exactly one" property is enforced by proper types and runtime checks where needed. Currently there are
 * three kinds of ops:
 *
 *  - DeterministicOp implementations participate in 1-bounded regions. For every external non-completion signal these
 *  ops produce *exactly one* signal (completion is different, explained later) therefore keeping the number of events
 *  the same: exactly one.
 *
 *  - DetachedOp implementations are boundaries between 1-bounded regions. This means that they need to enforce the
 *  "exactly one" property both on their upstream and downstream regions. As a consequence a DetachedOp can never
 *  answer an onPull with a ctxt.pull() or answer an onPush() with a ctxt.push() since such an action would "steal"
 *  the event from one region (resulting in zero signals) and would inject it to the other region (resulting in two
 *  signals). However DetachedOps have the ability to call ctxt.hold() as a response to onPush/onPull which temporarily
 *  takes the signal off and stops execution, at the same time putting the op in a "holding" state. If the op is in a
 *  holding state it contains one absorbed signal, therefore in this state the only possible command to call is
 *  ctxt.pushAndPull() which results in two events making the balance right again:
 *  1 hold + 1 external event = 2 external event
 *  This mechanism allows synchronization between the upstream and downstream regions which otherwise can progress
 *  independently.
 *
 *  - BoundaryOp implementations are meant to communicate with the external world. These ops do not have most of the
 *  safety properties enforced and should be used carefully. One important ability of BoundaryOps that they can take
 *  off an execution signal by calling ctxt.exit(). This is typically used immediately after an external signal has
 *  been produced (for example an actor message). BoundaryOps can also kickstart execution by calling enter() which
 *  returns a context they can use to inject signals into the interpreter. There is no checks in place to enforce that
 *  the number of signals taken out by exit() and the number of signals returned via enter() are the same -- using this
 *  op type needs extra care from the implementer.
 *  BoundaryOps are the elements that make the interpreter *tick*, there is no other way to start the interpreter
 *  than using a BoundaryOp.
 *
 * Operations are allowed to do early completion and cancel/complete their upstreams and downstreams. It is *not*
 * allowed however to do these independently to avoid isolated execution islands. The only call possible is ctxt.finish()
 * which is a combination of cancel/complete.
 * Since onComplete is not a backpressured signal it is sometimes preferable to push a final element and then immediately
 * finish. This combination is exposed as pushAndFinish() which enables op writers to propagate completion events without
 * waiting for an extra round of pull.
 * Another peculiarity is how to convert termination events (complete/failure) into elements. The problem
 * here is that the termination events are not backpressured while elements are. This means that simply calling ctxt.push()
 * as a response to onUpstreamFinished() will very likely break boundedness and result in a buffer overflow somewhere.
 * Therefore the only allowed command in this case is ctxt.absorbTermination() which stops the propagation of the
 * termination signal, and puts the op in a finishing state. Depending on whether the op has a pending pull signal it has
 * not yet "consumed" by a push its onPull() handler might be called immediately.
 *
 * In order to execute different individual execution regions the interpreter uses the callstack to schedule these. The
 * current execution forking operations are
 *  - ctxt.finish() which starts a wave of completion and cancellation in two directions. When an op calls finish()
 *  it is immediately replaced by an artificial Finished op which makes sure that the two execution paths are isolated
 *  forever.
 *  - ctxt.fail() which is similar to finish()
 *  - ctxt.pushAndPull() which (as a response to a previous ctxt.hold()) starts a wawe of downstream push and upstream
 *  pull. The two execution paths are isolated by the op itself since onPull() from downstream can only answered by hold or
 *  push, while onPush() from upstream can only answered by hold or pull -- it is impossible to "cross" the op.
 *  - ctxt.pushAndFinish() which is different from the forking ops above because the execution of push and finish happens on
 *  the same execution region and they are order dependent, too.
 * The interpreter tracks the depth of recursive forking and allows various strategies of dealing with the situation
 * when this depth reaches a certain limit. In the simplest case an error is reported (this is very useful for stress
 * testing and finding callstack wasting bugs), in the other case the forked call is scheduled via a list -- i.e. instead
 * of the stack the heap is used.
 */
class OneBoundedInterpreter(ops: Seq[Op[_, _, _, _, _]], val forkLimit: Int = 100, val overflowToHeap: Boolean = true) {
  import OneBoundedInterpreter._
  type UntypedOp = Op[Any, Any, Directive, Directive, DetachedContext[Any]]
  require(ops.nonEmpty, "OneBoundedInterpreter cannot be created without at least one Op")

  private val pipeline = ops.toArray.asInstanceOf[Array[UntypedOp]]

  /**
   * This table is used to accelerate demand propagation upstream. All ops that implement TransitivePullOp are guaranteed
   * to only do upstream propagation of demand signals, therefore it is not necessary to execute them but enough to
   * "jump over" them. This means that when a chain of one million maps gets a downstream demand it is propagated
   * to the upstream *in one step* instead of one million onPull() calls.
   * This table maintains the positions where execution should jump from a current position when a pull event is to
   * be executed.
   */
  private val jumpBacks: Array[Int] = calculateJumpBacks

  private val Upstream = 0
  private val Downstream = pipeline.length - 1

  // Var to hold the current element if pushing. The only reason why this var is needed is to avoid allocations and
  // make it possible for the Pushing state to be an object
  private var elementInFlight: Any = _
  // Points to the current point of execution inside the pipeline
  private var activeOp = -1
  // The current interpreter state that decides what happens at the next round
  private var state: State = Pushing

  // Counter that keeps track of the depth of recursive forked executions
  private var forkCount = 0
  // List that is used as an auxiliary stack if fork recursion depth reaches forkLimit
  private var overflowStack = List.empty[(Int, State, Any)]

  // see the jumpBacks variable for explanation
  private def calculateJumpBacks: Array[Int] = {
    val table = Array.ofDim[Int](pipeline.length)
    var nextJumpBack = -1
    for (pos ← 0 until pipeline.length) {
      table(pos) = nextJumpBack
      if (!pipeline(pos).isInstanceOf[TransitivePullOp[_, _]]) nextJumpBack = pos
    }
    table
  }

  private sealed trait State extends DetachedContext[Any] with BoundaryContext {
    def advance(): Unit

    override def push(elem: Any): DownstreamDirective = {
      if (pipeline(activeOp).holding) throw new IllegalStateException("Cannot push while holding, only pushAndPull")
      pipeline(activeOp).allowedToPush = false
      elementInFlight = elem
      state = Pushing
      PhantomDirective
    }

    override def pull(): UpstreamDirective = {
      if (pipeline(activeOp).holding) throw new IllegalStateException("Cannot pull while holding, only pushAndPull")
      pipeline(activeOp).allowedToPush = !pipeline(activeOp).isInstanceOf[DetachedOp[_, _]]
      state = Pulling
      PhantomDirective
    }

    override def finish(): FreeDirective = {
      fork(Completing)
      state = Cancelling
      PhantomDirective
    }

    override def pushAndFinish(elem: Any): DownstreamDirective = {
      pipeline(activeOp) = Finished.asInstanceOf[UntypedOp]
      // This MUST be an unsafeFork because the execution of PushFinish MUST strictly come before the finish execution
      // path. Other forks are not order dependent because they execute on isolated execution domains which cannot
      // "cross paths". This unsafeFork is relatively safe here because PushAndFinish simply absorbs all later downstream
      // calls of pushAndFinish since the finish event has been scheduled already.
      // It might be that there are some degenerate cases where this can blow up the stack with a very long chain but I
      // am not aware of such scenario yet. If you know one, put it in InterpreterStressSpec :)
      unsafeFork(PushFinish, elem)
      elementInFlight = null
      finish()
    }

    override def fail(cause: Throwable): FreeDirective = {
      fork(Failing(cause))
      state = Cancelling
      PhantomDirective
    }

    override def hold(): FreeDirective = {
      if (pipeline(activeOp).holding) throw new IllegalStateException("Cannot hold while already holding")
      pipeline(activeOp).holding = true
      exit()
    }

    override def pushAndPull(elem: Any): FreeDirective = {
      if (!pipeline(activeOp).holding) throw new IllegalStateException("Cannot pushAndPull without holding first")
      pipeline(activeOp).holding = false
      fork(Pushing, elem)
      state = Pulling
      PhantomDirective
    }

    override def absorbTermination(): TerminationDirective = {
      pipeline(activeOp).holding = false
      finish()
    }

    override def exit(): FreeDirective = {
      elementInFlight = null
      activeOp = -1
      PhantomDirective
    }
  }

  private object Pushing extends State {
    override def advance(): Unit = {
      activeOp += 1
      pipeline(activeOp).onPush(elementInFlight, ctxt = this)
    }
  }

  private object PushFinish extends State {
    override def advance(): Unit = {
      activeOp += 1
      pipeline(activeOp).onPush(elementInFlight, ctxt = this)
    }

    override def pushAndFinish(elem: Any): DownstreamDirective = {
      elementInFlight = elem
      state = PushFinish
      PhantomDirective
    }

    override def finish(): FreeDirective = {
      state = Completing
      PhantomDirective
    }
  }

  private object Pulling extends State {
    override def advance(): Unit = {
      elementInFlight = null
      activeOp = jumpBacks(activeOp)
      pipeline(activeOp).onPull(ctxt = this)
    }

    override def hold(): FreeDirective = {
      super.hold()
      pipeline(activeOp).allowedToPush = true
      PhantomDirective
    }
  }

  private object Completing extends State {
    override def advance(): Unit = {
      elementInFlight = null
      pipeline(activeOp) = Finished.asInstanceOf[UntypedOp]
      activeOp += 1
      if (!pipeline(activeOp).isFinishing) pipeline(activeOp).onUpstreamFinish(ctxt = this)
      else exit()
    }

    override def finish(): FreeDirective = {
      state = Completing
      PhantomDirective
    }

    override def absorbTermination(): TerminationDirective = {
      pipeline(activeOp).terminationPending = true
      pipeline(activeOp).holding = false
      // FIXME: This state is potentially corrupted by the jumpBackTable (not updated when jumping over)
      if (pipeline(activeOp).allowedToPush) pipeline(activeOp).onPull(ctxt = Pulling)
      else exit()
      PhantomDirective
    }
  }

  private object Cancelling extends State {
    override def advance(): Unit = {
      elementInFlight = null
      pipeline(activeOp) = Finished.asInstanceOf[UntypedOp]
      activeOp -= 1
      if (!pipeline(activeOp).isFinishing) pipeline(activeOp).onDownstreamFinish(ctxt = this)
      else exit()
    }

    override def finish(): FreeDirective = {
      state = Cancelling
      PhantomDirective
    }
  }

  private final case class Failing(cause: Throwable) extends State {
    override def advance(): Unit = {
      elementInFlight = null
      pipeline(activeOp) = Finished.asInstanceOf[UntypedOp]
      activeOp += 1
      pipeline(activeOp).onFailure(cause, ctxt = this)
    }

    override def absorbTermination(): TerminationDirective = {
      pipeline(activeOp).terminationPending = true
      pipeline(activeOp).holding = false
      if (pipeline(activeOp).allowedToPush) pipeline(activeOp).onPull(ctxt = Pulling)
      else exit()
      PhantomDirective
    }
  }

  @tailrec private def execute(): Unit = {
    while (activeOp > -1 && activeOp < pipeline.length) {
      try {
        state.advance()
      } catch {
        case NonFatal(e) ⇒
          try {
            state.fail(e)
          } catch {
            case NonFatal(_) ⇒
              // TODO: Make pipeline all failed
              throw new IllegalStateException("Double Fault: Failure while handling failure", e)
          }
      }
    }

    // Execute all delayed forks that were put on the heap if the fork limit has been reached
    if (overflowStack.nonEmpty) {
      val memo = overflowStack.head
      activeOp = memo._1
      state = memo._2
      elementInFlight = memo._3
      overflowStack = overflowStack.tail
      execute()
    }
  }

  /**
   * Forks off execution of the pipeline by saving current position, fully executing the effects of the given
   * forkState then setting back the position to the saved value.
   * By default forking is executed by using the callstack. If the depth of forking ever reaches the configured forkLimit
   * this method either fails (useful for testing) or starts using the heap instead of the callstack to avoid a
   * stack overflow.
   */
  private def fork(forkState: State, elem: Any = null): Unit = {
    forkCount += 1
    if (forkCount == forkLimit) {
      if (!overflowToHeap) throw new IllegalStateException("Fork limit reached")
      else overflowStack ::= ((activeOp, forkState, elem))
    } else unsafeFork(forkState, elem)
    forkCount -= 1
  }

  /**
   * Unsafe fork always uses the stack for execution. This call is needed by pushAndComplete where the forked execution
   * is order dependent since the push and complete events travel in the same direction and not isolated by a boundary
   */
  private def unsafeFork(forkState: State, elem: Any = null): Unit = {
    val savePos = activeOp
    elementInFlight = elem
    state = forkState
    execute()
    activeOp = savePos
    PhantomDirective
  }

  def init(): Unit = {
    initBoundaries()
    runDetached()
  }

  def isFinished: Boolean = pipeline(Upstream) == Finished && pipeline(Downstream) == Finished

  /**
   * This method injects a Context to each of the BoundaryOps. This will be the context returned by enter().
   */
  private def initBoundaries(): Unit = {
    var op = 0
    while (op < pipeline.length) {
      if (pipeline(op).isInstanceOf[BoundaryOp]) {
        pipeline(op).asInstanceOf[BoundaryOp].bctxt = new State {
          val entryPoint = op

          override def advance(): Unit = ()

          override def push(elem: Any): DownstreamDirective = {
            activeOp = entryPoint
            super.push(elem)
            execute()
            PhantomDirective
          }

          override def pull(): UpstreamDirective = {
            activeOp = entryPoint
            super.pull()
            execute()
            PhantomDirective
          }

          override def finish(): FreeDirective = {
            activeOp = entryPoint
            super.finish()
            execute()
            PhantomDirective
          }

          override def fail(cause: Throwable): FreeDirective = {
            activeOp = entryPoint
            super.fail(cause)
            execute()
            PhantomDirective
          }

          override def hold(): FreeDirective = {
            activeOp = entryPoint
            super.hold()
            execute()
            PhantomDirective
          }

          override def pushAndPull(elem: Any): FreeDirective = {
            activeOp = entryPoint
            super.pushAndPull(elem)
            execute()
            PhantomDirective
          }
        }
      }
      op += 1
    }
  }

  /**
   * Starts execution of detached regions.
   *
   * Since detached ops partition the pipeline into different 1-bounded domains is is necessary to inject a starting
   * signal into these regions (since there is no external signal that would kick off their execution otherwise).
   */
  private def runDetached(): Unit = {
    var op = pipeline.length - 1
    while (op >= 0) {
      if (pipeline(op).isInstanceOf[DetachedOp[_, _]]) {
        activeOp = op
        state = Pulling
        execute()
      }
      op -= 1
    }
  }

}