/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.StreamLayout
import akka.stream.{ Outlet, Shape, OutPort, Graph }
import scala.collection.immutable
import akka.stream.impl.Junctions.FlexiRouteModule

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
   * via the defined [[State]]. Handles completion, error and cancel via the defined
   * [[CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiRoute#createRouteLogic]].
   */
  abstract class RouteLogic[In] {
    def initialState: State[_]
    def initialCompletionHandling: CompletionHandling = defaultCompletionHandling

    /**
     * Context that is passed to the functions of [[State]] and [[CompletionHandling]].
     * The context provides means for performing side effects, such as emitting elements
     * downstream.
     */
    trait RouteLogicContext {
      /**
       * @return `true` if at least one element has been requested by the given downstream (output).
       */
      def isDemandAvailable(output: OutPort): Boolean

      /**
       * Emit one element downstream. It is only allowed to `emit` when
       * [[#isDemandAvailable]] is `true` for the given `output`, otherwise
       * `IllegalArgumentException` is thrown.
       */
      def emit[Out](output: Outlet[Out])(elem: Out): Unit

      /**
       * Complete the given downstream successfully.
       */
      def complete(output: OutPort): Unit

      /**
       * Complete all downstreams successfully and cancel upstream.
       */
      def complete(): Unit

      /**
       * Complete the given downstream with failure.
       */
      def error(output: OutPort, cause: Throwable): Unit

      /**
       * Complete all downstreams with failure and cancel upstream.
       */
      def error(cause: Throwable): Unit

      /**
       * Replace current [[CompletionHandling]].
       */
      def changeCompletionHandling(completion: CompletionHandling): Unit
    }

    /**
     * Definition of which outputs that must have requested elements and how to act
     * on the read elements. When an element has been read [[#onInput]] is called and
     * then it is ensured that the specified downstream outputs have requested at least
     * one element, i.e. it is allowed to emit at least one element downstream with
     * [[RouteLogicContext#emit]].
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
     * How to handle completion or error from upstream input and how to
     * handle cancel from downstream output.
     *
     * The `onComplete` function is called the upstream input was completed successfully.
     *
     * The `onError` function is called when the upstream input was completed with failure.
     *
     * The `onCancel` function is called when a downstream output cancels.
     * It returns next behavior or [[#SameState]] to keep current behavior.
     */
    sealed case class CompletionHandling(
      onComplete: RouteLogicContext ⇒ Unit,
      onError: (RouteLogicContext, Throwable) ⇒ Unit,
      onCancel: (RouteLogicContext, OutPort) ⇒ State[_])

    /**
     * When an output cancels it continues with remaining outputs.
     * Error or completion from upstream are immediately propagated.
     */
    val defaultCompletionHandling: CompletionHandling = CompletionHandling(
      onComplete = _ ⇒ (),
      onError = (ctx, cause) ⇒ (),
      onCancel = (ctx, _) ⇒ SameState)

    /**
     * Completes as soon as any output cancels.
     * Error or completion from upstream are immediately propagated.
     */
    val eagerClose: CompletionHandling = CompletionHandling(
      onComplete = _ ⇒ (),
      onError = (ctx, cause) ⇒ (),
      onCancel = (ctx, _) ⇒ { ctx.complete(); SameState })

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
abstract class FlexiRoute[In, S <: Shape](val shape: S, attributes: OperationAttributes) extends Graph[S, Unit] {
  import akka.stream.scaladsl.FlexiRoute._

  val module: StreamLayout.Module = new FlexiRouteModule(shape, createRouteLogic)

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
}
