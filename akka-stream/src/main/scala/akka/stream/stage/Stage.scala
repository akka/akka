/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.stage

import akka.NotUsed
import akka.stream._

import scala.util.control.NonFatal

/**
 * General interface for stream transformation.
 *
 * Custom `Stage` implementations are intended to be used with
 * [[akka.stream.scaladsl.FlowOps#transform]] or
 * [[akka.stream.javadsl.Flow#transform]] to extend the `Flow` API when there
 * is no specialized operator that performs the transformation.
 *
 * Custom implementations are subclasses of [[PushPullStage]] or
 * [[DetachedStage]]. Sometimes it is convenient to extend
 * [[StatefulStage]] for support of become like behavior.
 *
 * It is possible to keep state in the concrete `Stage` instance with
 * ordinary instance variables. The `Transformer` is executed by an actor and
 * therefore you do not have to add any additional thread safety or memory
 * visibility constructs to access the state from the callback methods.
 *
 * @see [[akka.stream.scaladsl.Flow#transform]]
 * @see [[akka.stream.javadsl.Flow#transform]]
 */
@deprecated("Please use GraphStage instead.", "2.4.2")
sealed trait Stage[-In, +Out]

/**
 * INTERNAL API
 */
private[stream] object AbstractStage {

  private class PushPullGraphLogic[In, Out](
    private val shape: FlowShape[In, Out],
    val attributes:    Attributes,
    val stage:         AbstractStage[In, Out, Directive, Directive, Context[Out], LifecycleContext])
    extends GraphStageLogic(shape) with DetachedContext[Out] {

    final override def materializer: Materializer = interpreter.materializer

    private def ctx: DetachedContext[Out] = this

    private var currentStage: AbstractStage[In, Out, Directive, Directive, Context[Out], LifecycleContext] = stage

    {
      // No need to refer to the handle in a private val
      val handler = new InHandler with OutHandler {
        override def onPush(): Unit =
          try { currentStage.onPush(grab(shape.in), ctx) } catch { case NonFatal(ex) ⇒ onSupervision(ex) }

        override def onPull(): Unit = currentStage.onPull(ctx)

        override def onUpstreamFinish(): Unit = currentStage.onUpstreamFinish(ctx)

        override def onUpstreamFailure(ex: Throwable): Unit = currentStage.onUpstreamFailure(ex, ctx)

        override def onDownstreamFinish(): Unit = currentStage.onDownstreamFinish(ctx)
      }

      setHandler(shape.in, handler)
      setHandler(shape.out, handler)
    }

    private def onSupervision(ex: Throwable): Unit = {
      currentStage.decide(ex) match {
        case Supervision.Stop ⇒
          failStage(ex)
        case Supervision.Resume ⇒
          resetAfterSupervise()
        case Supervision.Restart ⇒
          resetAfterSupervise()
          currentStage.postStop()
          currentStage = currentStage.restart().asInstanceOf[AbstractStage[In, Out, Directive, Directive, Context[Out], LifecycleContext]]
          currentStage.preStart(ctx)
      }
    }

    private def resetAfterSupervise(): Unit = {
      val mustPull = currentStage.isDetached || isAvailable(shape.out)
      if (!hasBeenPulled(shape.in) && mustPull) pull(shape.in)
    }

    override protected[stream] def beforePreStart(): Unit = {
      super.beforePreStart()
      if (currentStage.isDetached) pull(shape.in)
    }

    final override def push(elem: Out): DownstreamDirective = {
      push(shape.out, elem)
      null
    }

    final override def pull(): UpstreamDirective = {
      pull(shape.in)
      null
    }

    final override def finish(): FreeDirective = {
      completeStage()
      null
    }

    final override def pushAndFinish(elem: Out): DownstreamDirective = {
      push(shape.out, elem)
      completeStage()
      null
    }

    final override def fail(cause: Throwable): FreeDirective = {
      failStage(cause)
      null
    }

    final override def isFinishing: Boolean = isClosed(shape.in)

    final override def absorbTermination(): TerminationDirective = {
      if (isClosed(shape.out)) {
        val ex = new UnsupportedOperationException("It is not allowed to call absorbTermination() from onDownstreamFinish.")
        // This MUST be logged here, since the downstream has cancelled, i.e. there is no one to send onError to, the
        // stage is just about to finish so no one will catch it anyway just the interpreter

        interpreter.log.error(ex.getMessage)
        throw ex // We still throw for correctness (although a finish() would also work here)
      }
      if (isAvailable(shape.out)) currentStage.onPull(ctx)
      null
    }

    override def pushAndPull(elem: Out): FreeDirective = {
      push(shape.out, elem)
      pull(shape.in)
      null
    }

    final override def holdUpstreamAndPush(elem: Out): UpstreamDirective = {
      push(shape.out, elem)
      null
    }

    final override def holdDownstreamAndPull(): DownstreamDirective = {
      pull(shape.in)
      null
    }

    final override def isHoldingDownstream: Boolean = isAvailable(shape.out)

    final override def isHoldingUpstream: Boolean = !(isClosed(shape.in) || hasBeenPulled(shape.in))

    final override def holdDownstream(): DownstreamDirective = null

    final override def holdUpstream(): UpstreamDirective = null

    override def preStart(): Unit = currentStage.preStart(ctx)
    override def postStop(): Unit = currentStage.postStop()

    override def toString: String = s"PushPullGraphLogic($currentStage)"
  }

  class PushPullGraphStageWithMaterializedValue[-In, +Out, Ext, +Mat](
    val factory:     (Attributes) ⇒ (Stage[In, Out], Mat),
    stageAttributes: Attributes)
    extends GraphStageWithMaterializedValue[FlowShape[In, Out], Mat] {

    val name = stageAttributes.nameOrDefault()
    override def initialAttributes = stageAttributes
    val shape = FlowShape(Inlet[In](name + ".in"), Outlet[Out](name + ".out"))

    override def toString = name

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Mat) = {
      val stageAndMat = factory(inheritedAttributes)
      val stage: AbstractStage[In, Out, Directive, Directive, Context[Out], LifecycleContext] =
        stageAndMat._1.asInstanceOf[AbstractStage[In, Out, Directive, Directive, Context[Out], LifecycleContext]]
      (new PushPullGraphLogic(shape, inheritedAttributes, stage), stageAndMat._2)
    }
  }

  class PushPullGraphStage[-In, +Out, Ext](_factory: (Attributes) ⇒ Stage[In, Out], _stageAttributes: Attributes)
    extends PushPullGraphStageWithMaterializedValue[In, Out, Ext, NotUsed]((att: Attributes) ⇒ (_factory(att), NotUsed), _stageAttributes)
}

@deprecated("Please use GraphStage instead.", "2.4.2")
abstract class AbstractStage[-In, Out, PushD <: Directive, PullD <: Directive, Ctx <: Context[Out], LifeCtx <: LifecycleContext] extends Stage[In, Out] {

  /**
   * INTERNAL API
   */
  private[stream] def isDetached: Boolean = false

  /**
   * User overridable callback.
   * <p/>
   * It is called before any other method defined on the `Stage`.
   * Empty default implementation.
   */
  @throws(classOf[Exception])
  def preStart(ctx: LifeCtx): Unit = ()

  /**
   * `onPush` is called when an element from upstream is available and there is demand from downstream, i.e.
   * in `onPush` you are allowed to call [[akka.stream.stage.Context#push]] to emit one element downstream,
   * or you can absorb the element by calling [[akka.stream.stage.Context#pull]]. Note that you can only
   * emit zero or one element downstream from `onPull`.
   *
   * To emit more than one element you have to push the remaining elements from [[#onPull]], one-by-one.
   * `onPush` is not called again until `onPull` has requested more elements with
   * [[akka.stream.stage.Context#pull]].
   */
  def onPush(elem: In, ctx: Ctx): PushD

  /**
   * `onPull` is called when there is demand from downstream, i.e. you are allowed to push one element
   * downstream with [[akka.stream.stage.Context#push]], or request elements from upstreams with
   * [[akka.stream.stage.Context#pull]]
   */
  def onPull(ctx: Ctx): PullD

  /**
   * `onUpstreamFinish` is called when upstream has signaled that the stream is
   * successfully completed. Here you cannot call [[akka.stream.stage.Context#push]],
   * because there might not be any demand from downstream. To emit additional elements before
   * terminating you can use [[akka.stream.stage.Context#absorbTermination]] and push final elements
   * from [[#onPull]]. The stage will then be in finishing state, which can be checked
   * with [[akka.stream.stage.Context#isFinishing]].
   *
   * By default the finish signal is immediately propagated with [[akka.stream.stage.Context#finish]].
   *
   * *IMPORTANT NOTICE:* this signal is not back-pressured, it might arrive from upstream even though
   * the last action by this stage was a “push”.
   */
  def onUpstreamFinish(ctx: Ctx): TerminationDirective = ctx.finish()

  /**
   * `onDownstreamFinish` is called when downstream has canceled.
   *
   * By default the cancel signal is immediately propagated with [[akka.stream.stage.Context#finish]].
   */
  def onDownstreamFinish(ctx: Ctx): TerminationDirective = ctx.finish()

  /**
   * `onUpstreamFailure` is called when upstream has signaled that the stream is completed
   * with failure. It is not called if [[#onPull]] or [[#onPush]] of the stage itself
   * throws an exception.
   *
   * Note that elements that were emitted by upstream before the failure happened might
   * not have been received by this stage when `onUpstreamFailure` is called, i.e.
   * failures are not backpressured and might be propagated as soon as possible.
   *
   * Here you cannot call [[akka.stream.stage.Context#push]], because there might not
   * be any demand from  downstream. To emit additional elements before terminating you
   * can use [[akka.stream.stage.Context#absorbTermination]] and push final elements
   * from [[#onPull]]. The stage will then be in finishing state, which can be checked
   * with [[akka.stream.stage.Context#isFinishing]].
   */
  def onUpstreamFailure(cause: Throwable, ctx: Ctx): TerminationDirective = ctx.fail(cause)

  /**
   * User overridable callback.
   * <p/>
   * Is called after the Stages final action is performed.  // TODO need better wording here
   * Empty default implementation.
   */
  @throws(classOf[Exception])
  def postStop(): Unit = ()

  /**
   * If an exception is thrown from [[#onPush]] this method is invoked to decide how
   * to handle the exception. By default this method returns [[Supervision.Stop]].
   *
   * If an exception is thrown from [[#onPull]] the stream will always be completed with
   * failure, because it is not always possible to recover from that state.
   * In concrete stages it is of course possible to use ordinary try-catch-recover inside
   * `onPull` when it is know how to recover from such exceptions.
   *
   */
  def decide(t: Throwable): Supervision.Directive = Supervision.Stop

  /**
   * Used to create a fresh instance of the stage after an error resulting in a [[Supervision.Restart]]
   * directive. By default it will return the same instance untouched, so you must override it
   * if there are any state that should be cleared before restarting, e.g. by returning a new instance.
   */
  def restart(): Stage[In, Out] = this
}

/**
 * `PushPullStage` implementations participate in 1-bounded regions. For every external non-completion signal these
 * stages produce *exactly one* push or pull signal.
 *
 * [[#onPush]] is called when an element from upstream is available and there is demand from downstream, i.e.
 * in `onPush` you are allowed to call [[Context#push]] to emit one element downstream, or you can absorb the
 * element by calling [[Context#pull]]. Note that you can only emit zero or one element downstream from `onPull`.
 * To emit more than one element you have to push the remaining elements from [[#onPull]], one-by-one.
 * `onPush` is not called again until `onPull` has requested more elements with [[Context#pull]].
 *
 * [[StatefulStage]] has support for making it easy to emit more than one element from `onPush`.
 *
 * [[#onPull]] is called when there is demand from downstream, i.e. you are allowed to push one element
 * downstream with [[Context#push]], or request elements from upstreams with [[Context#pull]]. If you
 * always perform transitive pull by calling `ctx.pull` from `onPull` you can use [[PushStage]] instead of
 * `PushPullStage`.
 *
 * Stages are allowed to do early completion of downstream and cancel of upstream. This is done with [[Context#finish]],
 * which is a combination of cancel/complete.
 *
 * Since onComplete is not a backpressured signal it is sometimes preferable to push a final element and then
 * immediately finish. This combination is exposed as [[Context#pushAndFinish]] which enables stages to
 * propagate completion events without waiting for an extra round of pull.
 *
 * Another peculiarity is how to convert termination events (complete/failure) into elements. The problem
 * here is that the termination events are not backpressured while elements are. This means that simply calling
 * [[Context#push]] as a response to [[#onUpstreamFinish]] or [[#onUpstreamFailure]] will very likely break boundedness
 * and result in a buffer overflow somewhere. Therefore the only allowed command in this case is
 * [[Context#absorbTermination]] which stops the propagation of the termination signal, and puts the stage in a
 * [[akka.stream.stage.Context#isFinishing]] state. Depending on whether the stage has a pending pull signal it
 * has not yet "consumed" by a push its [[#onPull]] handler might be called immediately or later. From
 * [[#onPull]] final elements can be pushed before completing downstream with [[Context#finish]] or
 * [[Context#pushAndFinish]].
 *
 * [[StatefulStage]] has support for making it easy to emit final elements.
 *
 * All these rules are enforced by types and runtime checks where needed. Always return the `Directive`
 * from the call to the [[Context]] method, and do only call [[Context]] commands once per callback.
 *
 * @see [[DetachedStage]]
 * @see [[StatefulStage]]
 * @see [[PushStage]]
 */
@deprecated("Please use GraphStage instead.", "2.4.2")
abstract class PushPullStage[In, Out] extends AbstractStage[In, Out, SyncDirective, SyncDirective, Context[Out], LifecycleContext]

/**
 * `PushStage` is a [[PushPullStage]] that always perform transitive pull by calling `ctx.pull` from `onPull`.
 */
@deprecated("Please use GraphStage instead.", "2.4.2")
abstract class PushStage[In, Out] extends PushPullStage[In, Out] {
  /**
   * Always pulls from upstream.
   */
  final override def onPull(ctx: Context[Out]): SyncDirective = ctx.pull()
}

/**
 * `DetachedStage` can be used to implement operations similar to [[akka.stream.scaladsl.FlowOps#buffer buffer]],
 * [[akka.stream.scaladsl.FlowOps#expand expand]] and [[akka.stream.scaladsl.FlowOps#conflate conflate]].
 *
 * `DetachedStage` implementations are boundaries between 1-bounded regions. This means that they need to enforce the
 * "exactly one" property both on their upstream and downstream regions. As a consequence a `DetachedStage` can never
 * answer an [[#onPull]] with a [[Context#pull]] or answer an [[#onPush]] with a [[Context#push]] since such an action
 * would "steal" the event from one region (resulting in zero signals) and would inject it to the other region
 * (resulting in two signals).
 *
 * However, DetachedStages have the ability to call [[akka.stream.stage.DetachedContext#hold]] as a response to
 * [[#onPush]] and [[#onPull]] which temporarily takes the signal off and
 * stops execution, at the same time putting the stage in an [[akka.stream.stage.DetachedContext#isHolding]] state.
 * If the stage is in a holding state it contains one absorbed signal, therefore in this state the only possible
 * command to call is [[akka.stream.stage.DetachedContext#pushAndPull]] which results in two events making the
 * balance right again: 1 hold + 1 external event = 2 external event
 *
 * This mechanism allows synchronization between the upstream and downstream regions which otherwise can progress
 * independently.
 *
 * @see [[PushPullStage]]
 */
@deprecated("Please use GraphStage instead.", "2.4.2")
abstract class DetachedStage[In, Out]
  extends AbstractStage[In, Out, UpstreamDirective, DownstreamDirective, DetachedContext[Out], LifecycleContext] {
  private[stream] override def isDetached = true

  /**
   * If an exception is thrown from [[#onPush]] this method is invoked to decide how
   * to handle the exception. By default this method returns [[Supervision.Stop]].
   *
   * If an exception is thrown from [[#onPull]] or if the stage is holding state the stream
   * will always be completed with failure, because it is not always possible to recover from
   * that state.
   * In concrete stages it is of course possible to use ordinary try-catch-recover inside
   * `onPull` when it is know how to recover from such exceptions.
   */
  override def decide(t: Throwable): Supervision.Directive = super.decide(t)
}

/**
 * The behavior of [[StatefulStage]] is defined by these two methods, which
 * has the same semantics as corresponding methods in [[PushPullStage]].
 */
abstract class StageState[In, Out] {
  def onPush(elem: In, ctx: Context[Out]): SyncDirective
  def onPull(ctx: Context[Out]): SyncDirective = ctx.pull()
}

/**
 * INTERNAL API
 */
private[akka] object StatefulStage {
  sealed trait AndThen
  case object Finish extends AndThen
  final case class Become(state: StageState[Any, Any]) extends AndThen
  case object Stay extends AndThen
}

/**
 * `StatefulStage` is a [[PushPullStage]] that provides convenience to make some things easier.
 *
 * The behavior is defined in [[StageState]] instances. The initial behavior is specified
 * by subclass implementing the [[#initial]] method. The behavior can be changed by using [[#become]].
 *
 * Use [[#emit]] or [[#emitAndFinish]] to push more than one element from [[StageState#onPush]] or
 * [[StageState#onPull]].
 *
 * Use [[#terminationEmit]] to push final elements from [[#onUpstreamFinish]] or [[#onUpstreamFailure]].
 */
@deprecated("StatefulStage is deprecated, please use GraphStage instead.", "2.0-M2")
abstract class StatefulStage[In, Out] extends PushPullStage[In, Out] {
  import StatefulStage._

  /**
   * Scala API
   */
  abstract class State extends StageState[In, Out]

  private[this] var emitting = false
  private[this] var _current: StageState[In, Out] = _
  become(initial)

  /**
   * Concrete subclass must return the initial behavior from this method.
   *
   * **Warning:** This method must not be implemented as `val`.
   */
  def initial: StageState[In, Out]

  /**
   * Current state.
   */
  final def current: StageState[In, Out] = _current

  /**
   * Change the behavior to another [[StageState]].
   */
  final def become(state: StageState[In, Out]): Unit = {
    require(state ne null, "New state must not be null")
    _current = state
  }

  /**
   * Invokes current state.
   */
  final override def onPush(elem: In, ctx: Context[Out]): SyncDirective = _current.onPush(elem, ctx)
  /**
   * Invokes current state.
   */
  final override def onPull(ctx: Context[Out]): SyncDirective = _current.onPull(ctx)

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective =
    if (emitting) ctx.absorbTermination()
    else ctx.finish()

  /**
   * Scala API: Can be used from [[StageState#onPush]] or [[StageState#onPull]] to push more than one
   * element downstream.
   */
  final def emit(iter: Iterator[Out], ctx: Context[Out]): SyncDirective = emit(iter, ctx, _current)

  /**
   * Java API: Can be used from [[StageState#onPush]] or [[StageState#onPull]] to push more than one
   * element downstream.
   */
  final def emit(iter: java.util.Iterator[Out], ctx: Context[Out]): SyncDirective = {
    import scala.collection.JavaConverters._
    emit(iter.asScala, ctx)
  }

  /**
   * Scala API: Can be used from [[StageState#onPush]] or [[StageState#onPull]] to push more than one
   * element downstream and after that change behavior.
   */
  final def emit(iter: Iterator[Out], ctx: Context[Out], nextState: StageState[In, Out]): SyncDirective = {
    if (emitting) throw new IllegalStateException("already in emitting state")
    if (iter.isEmpty) {
      become(nextState)
      ctx.pull()
    } else {
      val elem = iter.next()
      if (iter.hasNext) {
        emitting = true
        become(emittingState(iter, andThen = Become(nextState.asInstanceOf[StageState[Any, Any]])))
      } else
        become(nextState)
      ctx.push(elem)
    }
  }

  /**
   * Java API: Can be used from [[StageState#onPush]] or [[StageState#onPull]] to push more than one
   * element downstream and after that change behavior.
   */
  final def emit(iter: java.util.Iterator[Out], ctx: Context[Out], nextState: StageState[In, Out]): SyncDirective = {
    import scala.collection.JavaConverters._
    emit(iter.asScala, ctx, nextState)
  }

  /**
   * Scala API: Can be used from [[StageState#onPush]] or [[StageState#onPull]] to push more than one
   * element downstream and after that finish (complete downstreams, cancel upstreams).
   */
  final def emitAndFinish(iter: Iterator[Out], ctx: Context[Out]): SyncDirective = {
    if (emitting) throw new IllegalStateException("already in emitting state")
    if (iter.isEmpty)
      ctx.finish()
    else {
      val elem = iter.next()
      if (iter.hasNext) {
        emitting = true
        become(emittingState(iter, andThen = Finish))
        ctx.push(elem)
      } else
        ctx.pushAndFinish(elem)
    }
  }

  /**
   * Java API: Can be used from [[StageState#onPush]] or [[StageState#onPull]] to push more than one
   * element downstream and after that finish (complete downstreams, cancel upstreams).
   */
  final def emitAndFinish(iter: java.util.Iterator[Out], ctx: Context[Out]): SyncDirective = {
    import scala.collection.JavaConverters._
    emitAndFinish(iter.asScala, ctx)
  }

  /**
   * Scala API: Can be used from [[#onUpstreamFinish]] to push final elements downstream
   * before completing the stream successfully. Note that if this is used from
   * [[#onUpstreamFailure]] the failure will be absorbed and the stream will be completed
   * successfully.
   */
  final def terminationEmit(iter: Iterator[Out], ctx: Context[Out]): TerminationDirective = {
    if (iter.isEmpty) {
      if (emitting) ctx.absorbTermination()
      else ctx.finish()
    } else {
      val nextState = current match {
        case es: EmittingState if emitting ⇒ es.copy(iter = es.iter ++ iter)
        case _                             ⇒ emittingState(iter, andThen = Finish)
      }
      become(nextState)
      ctx.absorbTermination()
    }
  }

  /**
   * Java API: Can be used from [[#onUpstreamFinish]] or [[#onUpstreamFailure]] to push final
   * elements downstream.
   */
  final def terminationEmit(iter: java.util.Iterator[Out], ctx: Context[Out]): TerminationDirective = {
    import scala.collection.JavaConverters._
    terminationEmit(iter.asScala, ctx)
  }

  private def emittingState(iter: Iterator[Out], andThen: AndThen) = EmittingState(iter, andThen)

  private case class EmittingState(iter: Iterator[Out], andThen: AndThen) extends State {
    override def onPush(elem: In, ctx: Context[Out]) = throw new IllegalStateException("onPush not allowed in emittingState")
    override def onPull(ctx: Context[Out]) = {
      if (iter.hasNext) {
        val elem = iter.next()
        if (iter.hasNext)
          ctx.push(elem)
        else if (!ctx.isFinishing) {
          emitting = false
          andThen match {
            case Stay             ⇒ // ok
            case Become(newState) ⇒ become(newState.asInstanceOf[StageState[In, Out]])
            case Finish           ⇒ ctx.pushAndFinish(elem)
          }
          ctx.push(elem)
        } else
          ctx.pushAndFinish(elem)
      } else
        throw new IllegalStateException("onPull with empty iterator is not expected in emittingState")
    }
  }

}

/**
 * Return type from [[Context]] methods.
 */
sealed trait Directive
sealed trait AsyncDirective extends Directive
sealed trait SyncDirective extends Directive
sealed trait UpstreamDirective extends SyncDirective
sealed trait DownstreamDirective extends SyncDirective
sealed trait TerminationDirective extends SyncDirective
// never instantiated
sealed abstract class FreeDirective private () extends UpstreamDirective with DownstreamDirective with TerminationDirective with AsyncDirective

trait LifecycleContext {
  /**
   * Returns the Materializer that was used to materialize this [[Stage]].
   * It can be used to materialize sub-flows.
   */
  def materializer: Materializer

  /** Returns operation attributes associated with the this Stage */
  def attributes: Attributes
}

/**
 * Passed to the callback methods of [[PushPullStage]] and [[StatefulStage]].
 */
sealed trait Context[Out] extends LifecycleContext {
  /**
   * Push one element to downstreams.
   */
  def push(elem: Out): DownstreamDirective
  /**
   * Request for more elements from upstreams.
   */
  def pull(): UpstreamDirective
  /**
   * Cancel upstreams and complete downstreams successfully.
   */
  def finish(): FreeDirective
  /**
   * Push one element to downstream immediately followed by
   * cancel of upstreams and complete of downstreams.
   */
  def pushAndFinish(elem: Out): DownstreamDirective
  /**
   * Cancel upstreams and complete downstreams with failure.
   */
  def fail(cause: Throwable): FreeDirective
  /**
   * Puts the stage in a finishing state so that
   * final elements can be pushed from `onPull`.
   */
  def absorbTermination(): TerminationDirective

  /**
   * This returns `true` after [[#absorbTermination]] has been used.
   */
  def isFinishing: Boolean

}

/**
 * Passed to the callback methods of [[DetachedStage]].
 *
 * [[#hold]] stops execution and at the same time putting the stage in a holding state.
 * If the stage is in a holding state it contains one absorbed signal, therefore in
 * this state the only possible command to call is [[#pushAndPull]] which results in two
 * events making the balance right again: 1 hold + 1 external event = 2 external event
 */
trait DetachedContext[Out] extends Context[Out] {
  def holdUpstream(): UpstreamDirective
  def holdUpstreamAndPush(elem: Out): UpstreamDirective

  def holdDownstream(): DownstreamDirective
  def holdDownstreamAndPull(): DownstreamDirective

  /**
   * This returns `true` when [[#hold]] has been used
   * and it is reset to `false` after [[#pushAndPull]].
   */
  def isHoldingBoth: Boolean = isHoldingUpstream && isHoldingDownstream
  def isHoldingUpstream: Boolean
  def isHoldingDownstream: Boolean

  def pushAndPull(elem: Out): FreeDirective

}
