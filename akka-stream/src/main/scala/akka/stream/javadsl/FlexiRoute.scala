/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import scala.annotation.varargs
import akka.stream.scaladsl
import scala.collection.immutable
import java.util.{ List ⇒ JList }
import akka.japi.Util.immutableIndexedSeq
import akka.stream._
import akka.stream.impl.StreamLayout
import akka.stream.impl.Junctions.FlexiRouteModule
import akka.stream.impl.Stages.DefaultAttributes

object FlexiRoute {

  sealed trait DemandCondition[T]

  /**
   * Demand condition for the [[State]] that will be
   * fulfilled when there are requests for elements from one specific downstream
   * output.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  class DemandFrom[T](val output: Outlet[T]) extends DemandCondition[Outlet[T]]

  /**
   * Demand condition for the [[State]] that will be
   * fulfilled when there are requests for elements from any of the given downstream
   * outputs.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  class DemandFromAny(val outputs: JList[OutPort]) extends DemandCondition[OutPort]

  /**
   * Demand condition for the [[State]] that will be
   * fulfilled when there are requests for elements from all of the given downstream
   * outputs.
   *
   * Cancelled and completed outputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  class DemandFromAll(val outputs: JList[OutPort]) extends DemandCondition[Unit]

  /**
   * Context that is passed to the `onInput` function of [[State]].
   * The context provides means for performing side effects, such as emitting elements
   * downstream.
   */
  trait RouteLogicContext[In] extends RouteLogicContextBase[In] {
    /**
     * Emit one element downstream. It is only allowed to `emit` at most one element to
     * each output in response to `onInput`, `IllegalStateException` is thrown.
     */
    def emit[T](output: Outlet[T], elem: T): Unit
  }

  trait RouteLogicContextBase[In] {
    /**
     * Complete the given downstream successfully.
     */
    def finish(output: OutPort): Unit

    /**
     * Complete all downstreams successfully and cancel upstream.
     */
    def finish(): Unit

    /**
     * Complete the given downstream with failure.
     */
    def fail(output: OutPort, cause: Throwable): Unit

    /**
     * Complete all downstreams with failure and cancel upstream.
     */
    def fail(cause: Throwable): Unit

    /**
     * Replace current [[CompletionHandling]].
     */
    def changeCompletionHandling(completion: CompletionHandling[In]): Unit
  }

  /**
   * How to handle completion or failure from upstream input and how to
   * handle cancel from downstream output.
   *
   * The `onUpstreamFinish` method is called when the upstream input was completed successfully.
   * The completion will be propagated downstreams unless this function throws an exception, in
   * which case the streams will be completed with that failure.
   *
   * The `onUpstreamFailure` method is called when the upstream input was completed with failure.
   * The failure will be propagated downstreams unless this function throws an exception, in
   * which case the streams will be completed with that failure instead.
   *
   * The `onDownstreamFinish` method is called when a downstream output cancels.
   * It returns next behavior or [[#sameState]] to keep current behavior.
   *
   * It is not possible to emit elements from the completion handling, since completion
   * handlers may be invoked at any time (without regard to downstream demand being available).
   */
  abstract class CompletionHandling[In] {
    def onUpstreamFinish(ctx: RouteLogicContextBase[In]): Unit
    def onUpstreamFailure(ctx: RouteLogicContextBase[In], cause: Throwable): Unit
    def onDownstreamFinish(ctx: RouteLogicContextBase[In], output: OutPort): State[_, In]
  }

  /**
   * Definition of which outputs that must have requested elements and how to act
   * on the read elements. When an element has been read [[#onInput]] is called and
   * then it is ensured that the specified downstream outputs have requested at least
   * one element, i.e. it is allowed to emit at most one element to each downstream
   * output with [[RouteLogicContext#emit]].
   *
   * The `onInput` method is called when an `element` was read from upstream.
   * The function returns next behavior or [[#sameState]] to keep current behavior.
   */
  abstract class State[T, In](val condition: DemandCondition[T]) {
    def onInput(ctx: RouteLogicContext[In], output: T, element: In): State[_, In]
  }

  /**
   * The possibly stateful logic that reads from the input and enables emitting to downstream
   * via the defined [[State]]. Handles completion, failure and cancel via the defined
   * [[CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiRoute#createRouteLogic]].
   */
  abstract class RouteLogic[In] {

    def initialState: State[_, In]
    def initialCompletionHandling: CompletionHandling[In] = defaultCompletionHandling

    /**
     * This method is executed during the startup of this stage, before the processing of the elements
     * begin.
     */
    def preStart(): Unit = ()

    /**
     * This method is executed during shutdown of this stage, after processing of elements stopped, or the
     * stage failed.
     */
    def postStop(): Unit = ()

    /**
     * Return this from [[State]] `onInput` to use same state for next element.
     */
    def sameState[T]: State[T, In] = FlexiRoute.sameStateInstance.asInstanceOf[State[T, In]]

    /**
     * Convenience to create a [[DemandFromAny]] condition.
     */
    @varargs def demandFromAny(outputs: OutPort*): DemandFromAny = {
      import scala.collection.JavaConverters._
      new DemandFromAny(outputs.asJava)
    }

    /**
     * Convenience to create a [[DemandFromAll]] condition.
     */
    @varargs def demandFromAll(outputs: OutPort*): DemandFromAll = {
      import scala.collection.JavaConverters._
      new DemandFromAll(outputs.asJava)
    }

    /**
     * Convenience to create a [[DemandFrom]] condition.
     */
    def demandFrom[T](output: Outlet[T]): DemandFrom[T] = new DemandFrom(output)

    /**
     * When an output cancels it continues with remaining outputs.
     */
    def defaultCompletionHandling: CompletionHandling[In] =
      new CompletionHandling[In] {
        override def onUpstreamFinish(ctx: RouteLogicContextBase[In]): Unit = ()
        override def onUpstreamFailure(ctx: RouteLogicContextBase[In], cause: Throwable): Unit = ()
        override def onDownstreamFinish(ctx: RouteLogicContextBase[In], output: OutPort): State[_, In] =
          sameState
      }

    /**
     * Completes as soon as any output cancels.
     */
    def eagerClose[A]: CompletionHandling[In] =
      new CompletionHandling[In] {
        override def onUpstreamFinish(ctx: RouteLogicContextBase[In]): Unit = ()
        override def onUpstreamFailure(ctx: RouteLogicContextBase[In], cause: Throwable): Unit = ()
        override def onDownstreamFinish(ctx: RouteLogicContextBase[In], output: OutPort): State[_, In] = {
          ctx.finish()
          sameState
        }
      }
  }

  private val sameStateInstance = new State[OutPort, Any](new DemandFromAny(java.util.Collections.emptyList[OutPort])) {
    override def onInput(ctx: RouteLogicContext[Any], output: OutPort, element: Any): State[_, Any] =
      throw new UnsupportedOperationException("SameState.onInput should not be called")

    override def toString: String = "SameState"
  }

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    class RouteLogicWrapper[In](delegate: RouteLogic[In]) extends scaladsl.FlexiRoute.RouteLogic[In] {

      override def preStart(): Unit = delegate.preStart()
      override def postStop(): Unit = delegate.postStop()

      override def initialState: this.State[_] = wrapState(delegate.initialState)

      override def initialCompletionHandling: this.CompletionHandling =
        wrapCompletionHandling(delegate.initialCompletionHandling)

      private def wrapState[T](delegateState: FlexiRoute.State[T, In]): State[T] =
        if (sameStateInstance == delegateState)
          SameState
        else
          State[T](convertDemandCondition(delegateState.condition)) { (ctx, outputHandle, elem) ⇒
            val newDelegateState =
              delegateState.onInput(new RouteLogicContextWrapper(ctx), outputHandle, elem)
            wrapState(newDelegateState)
          }

      private def wrapCompletionHandling(
        delegateCompletionHandling: FlexiRoute.CompletionHandling[In]): CompletionHandling =
        CompletionHandling(
          onUpstreamFinish = ctx ⇒ {
            delegateCompletionHandling.onUpstreamFinish(new RouteLogicContextBaseWrapper(ctx))
          },
          onUpstreamFailure = (ctx, cause) ⇒ {
            delegateCompletionHandling.onUpstreamFailure(new RouteLogicContextBaseWrapper(ctx), cause)
          },
          onDownstreamFinish = (ctx, outputHandle) ⇒ {
            val newDelegateState = delegateCompletionHandling.onDownstreamFinish(
              new RouteLogicContextBaseWrapper(ctx), outputHandle)
            wrapState(newDelegateState)
          })

      class RouteLogicContextWrapper(delegate: RouteLogicContext)
        extends RouteLogicContextBaseWrapper(delegate) with FlexiRoute.RouteLogicContext[In] {
        override def emit[T](output: Outlet[T], elem: T): Unit = delegate.emit(output)(elem)
      }
      class RouteLogicContextBaseWrapper(delegate: RouteLogicContextBase) extends FlexiRoute.RouteLogicContextBase[In] {
        override def finish(): Unit = delegate.finish()
        override def finish(output: OutPort): Unit = delegate.finish(output)
        override def fail(cause: Throwable): Unit = delegate.fail(cause)
        override def fail(output: OutPort, cause: Throwable): Unit = delegate.fail(output, cause)
        override def changeCompletionHandling(completion: FlexiRoute.CompletionHandling[In]): Unit =
          delegate.changeCompletionHandling(wrapCompletionHandling(completion))
      }

    }

    private def toAnyRefSeq(l: JList[OutPort]) = immutableIndexedSeq(l).asInstanceOf[immutable.Seq[Outlet[AnyRef]]]

    def convertDemandCondition[T](condition: DemandCondition[T]): scaladsl.FlexiRoute.DemandCondition[T] =
      condition match {
        case c: DemandFromAny ⇒ scaladsl.FlexiRoute.DemandFromAny(immutableIndexedSeq(c.outputs))
        case c: DemandFromAll ⇒ scaladsl.FlexiRoute.DemandFromAll(immutableIndexedSeq(c.outputs))
        case c: DemandFrom[_] ⇒ scaladsl.FlexiRoute.DemandFrom(c.output)
      }

  }
}

/**
 * Base class for implementing custom route junctions.
 * Such a junction always has one [[#in]] port and one or more output ports.
 * The output ports are to be defined in the concrete subclass and are created with
 * [[#createOutputPort]].
 *
 * The concrete subclass must implement [[#createRouteLogic]] to define the [[FlexiRoute#RouteLogic]]
 * that will be used when reading input elements and emitting output elements.
 * The [[FlexiRoute#RouteLogic]] instance may be stateful, but the ``FlexiRoute`` instance
 * must not hold mutable state, since it may be shared across several materialized ``FlowGraph``
 * instances.
 *
 * Note that a `FlexiRoute` instance can only be used at one place in the `FlowGraph` (one vertex).
 *
 * @param attributes optional attributes for this vertex
 */
abstract class FlexiRoute[In, S <: Shape](val shape: S, val attributes: Attributes) extends Graph[S, Unit] {
  import FlexiRoute._

  /**
   * INTERNAL API
   */
  private[stream] val module: StreamLayout.Module =
    new FlexiRouteModule(shape, (s: S) ⇒ new Internal.RouteLogicWrapper(createRouteLogic(s)),
      attributes and DefaultAttributes.flexiRoute)

  /**
   * Create the stateful logic that will be used when reading input elements
   * and emitting output elements. Create a new instance every time.
   */
  def createRouteLogic(s: S): RouteLogic[In]

  override def toString = attributes.nameLifted match {
    case Some(n) ⇒ n
    case None    ⇒ super.toString
  }

  override def withAttributes(attr: Attributes): Graph[S, Unit] =
    throw new UnsupportedOperationException(
      "withAttributes not supported by default by FlexiRoute, subclass may override and implement it")

  override def named(name: String): Graph[S, Unit] = withAttributes(Attributes.name(name))

}
