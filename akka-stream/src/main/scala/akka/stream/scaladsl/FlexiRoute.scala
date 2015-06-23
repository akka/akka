/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.StreamLayout
import akka.stream.{ Outlet, Shape, OutPort, Graph, Attributes }
import scala.collection.immutable
import akka.stream.impl.Junctions.FlexiRouteModule
import akka.stream.impl.Stages.DefaultAttributes

object FlexiRoute {

  import akka.stream.impl.StreamLayout

  sealed trait DemandCondition[+T]

  /**
   * Demand condition for the [[RouteLogic#State]] that will be
   * fulfilled when there are requests for elements from one specific downstream
   * output.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  final case class DemandFrom[+T](output: Outlet[T]) extends DemandCondition[Outlet[T]]

  object DemandFromAny {
    def apply(outputs: OutPort*): DemandFromAny = new DemandFromAny(outputs.to[immutable.Seq])
    def apply(p: Shape): DemandFromAny = new DemandFromAny(p.outlets)
  }
  /**
   * Demand condition for the [[RouteLogic#State]] that will be
   * fulfilled when there are requests for elements from any of the given downstream
   * outputs.
   *
   * Cancelled and completed outputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  final case class DemandFromAny(outputs: immutable.Seq[OutPort]) extends DemandCondition[OutPort]

  object DemandFromAll {
    def apply(outputs: OutPort*): DemandFromAll = new DemandFromAll(outputs.to[immutable.Seq])
    def apply(p: Shape): DemandFromAll = new DemandFromAll(p.outlets)
  }
  /**
   * Demand condition for the [[RouteLogic#State]] that will be
   * fulfilled when there are requests for elements from all of the given downstream
   * outputs.
   *
   * Cancelled and completed outputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  final case class DemandFromAll(outputs: immutable.Seq[OutPort]) extends DemandCondition[Unit]

  /**
   * The possibly stateful logic that reads from the input and enables emitting to downstream
   * via the defined [[State]]. Handles completion, failure and cancel via the defined
   * [[CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiRoute#createRouteLogic]].
   */
  abstract class RouteLogic[In] {
    def initialState: State[_]
    def initialCompletionHandling: CompletionHandling = defaultCompletionHandling

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
     * Context that is passed to the `onInput` function of [[State]].
     * The context provides means for performing side effects, such as emitting elements
     * downstream.
     */
    trait RouteLogicContext extends RouteLogicContextBase {
      /**
       * Emit one element downstream. It is only allowed to `emit` at most one element to
       * each output in response to `onInput`, `IllegalStateException` is thrown.
       */
      def emit[Out](output: Outlet[Out])(elem: Out): Unit
    }

    trait RouteLogicContextBase {
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
      def changeCompletionHandling(completion: CompletionHandling): Unit
    }

    /**
     * Definition of which outputs that must have requested elements and how to act
     * on the read elements. When an element has been read [[#onInput]] is called and
     * then it is ensured that the specified downstream outputs have requested at least
     * one element, i.e. it is allowed to emit at most one element to each downstream
     * output with [[RouteLogicContext#emit]].
     *
     * The `onInput` function is called when an `element` was read from upstream.
     * The function returns next behavior or [[#SameState]] to keep current behavior.
     */
    sealed case class State[Out](condition: DemandCondition[Out])(
      val onInput: (RouteLogicContext, Out, In) ⇒ State[_])

    /**
     * Return this from [[State]] `onInput` to use same state for next element.
     */
    def SameState[T]: State[T] = sameStateInstance.asInstanceOf[State[T]]

    private val sameStateInstance = new State(DemandFromAny(Nil))((_, _, _) ⇒
      throw new UnsupportedOperationException("SameState.onInput should not be called")) {

      // unique instance, don't use case class
      override def equals(other: Any): Boolean = super.equals(other)
      override def hashCode: Int = super.hashCode
      override def toString: String = "SameState"
    }

    /**
     * How to handle completion or failure from upstream input and how to
     * handle cancel from downstream output.
     *
     * The `onUpstreamFinish` function is called the upstream input was completed successfully.
     * The completion will be propagated downstreams unless this function throws an exception, in
     * which case the streams will be completed with that failure.
     *
     * The `onUpstreamFailure` function is called when the upstream input was completed with failure.
     * The failure will be propagated downstreams unless this function throws an exception, in
     * which case the streams will be completed with that failure instead.
     *
     * The `onDownstreamFinish` function is called when a downstream output cancels.
     * It returns next behavior or [[#SameState]] to keep current behavior.
     *
     * It is not possible to emit elements from the completion handling, since completion
     * handlers may be invoked at any time (without regard to downstream demand being available).
     */
    sealed case class CompletionHandling(
      onUpstreamFinish: RouteLogicContextBase ⇒ Unit,
      onUpstreamFailure: (RouteLogicContextBase, Throwable) ⇒ Unit,
      onDownstreamFinish: (RouteLogicContextBase, OutPort) ⇒ State[_])

    /**
     * When an output cancels it continues with remaining outputs.
     */
    val defaultCompletionHandling: CompletionHandling = CompletionHandling(
      onUpstreamFinish = _ ⇒ (),
      onUpstreamFailure = (ctx, cause) ⇒ (),
      onDownstreamFinish = (ctx, _) ⇒ SameState)

    /**
     * Completes as soon as any output cancels.
     */
    val eagerClose: CompletionHandling = CompletionHandling(
      onUpstreamFinish = _ ⇒ (),
      onUpstreamFailure = (ctx, cause) ⇒ (),
      onDownstreamFinish = (ctx, _) ⇒ { ctx.finish(); SameState })

  }

}

/**
 * Base class for implementing custom route junctions.
 * Such a junction always has one `in` port and one or more `out` ports.
 * The ports need to be defined by the concrete subclass by providing them as a constructor argument
 * to the [[FlexiRoute]] base class.
 *
 * The concrete subclass must implement [[#createRouteLogic]] to define the [[FlexiRoute#RouteLogic]]
 * that will be used when reading input elements and emitting output elements.
 * The [[FlexiRoute#RouteLogic]] instance may be stateful, but the ``FlexiRoute`` instance
 * must not hold mutable state, since it may be shared across several materialized ``FlowGraph``
 * instances.
 *
 * @param ports ports that this junction exposes
 * @param attributes optional attributes for this junction
 */
abstract class FlexiRoute[In, S <: Shape](val shape: S, attributes: Attributes) extends Graph[S, Unit] {
  import akka.stream.scaladsl.FlexiRoute._

  /**
   * INTERNAL API
   */
  private[stream] val module: StreamLayout.Module =
    new FlexiRouteModule(shape, createRouteLogic, attributes and DefaultAttributes.flexiRoute)

  /**
   * This allows a type-safe mini-DSL for selecting one of several ports, very useful in
   * conjunction with DemandFromAny(...):
   *
   * {{{
   * State(DemandFromAny(p1, p2, p2)) { (ctx, out, element) =>
   *   ctx.emit((p1 | p2 | p3)(out))(element)
   * }
   * }}}
   *
   * This will ensure that the either of the three ports would accept the type of `element`.
   */
  implicit class PortUnion[L](left: Outlet[L]) {
    def |[R <: L](right: Outlet[R]): InnerPortUnion[R] = new InnerPortUnion(Map((left, left.asInstanceOf[Outlet[R]]), (right, right)))
    /*
     * It would be nicer to use `Map[OutP, OutPort[_ <: T]]` to get rid of the casts,
     * but unfortunately this kills the compiler (and quite violently so).
     */
    class InnerPortUnion[T] private[PortUnion] (ports: Map[OutPort, Outlet[T]]) {
      def |[R <: T](right: Outlet[R]): InnerPortUnion[R] = new InnerPortUnion(ports.asInstanceOf[Map[OutPort, Outlet[R]]].updated(right, right))
      def apply(p: OutPort) = ports get p match {
        case Some(p) ⇒ p
        case None    ⇒ throw new IllegalStateException(s"port $p was not among the allowed ones (${ports.keys.mkString(", ")})")
      }
      def all: Iterable[Outlet[T]] = ports.values
    }
  }

  type PortT = S

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
