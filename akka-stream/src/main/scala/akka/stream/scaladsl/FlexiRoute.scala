/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.impl.Ast
import akka.stream.impl.Ast.Defaults._
import akka.stream.impl.FlexiRouteImpl.RouteLogicFactory

object FlexiRoute {

  /**
   * @see [[OutputPort]]
   */
  trait OutputHandle {
    private[akka] def portIndex: Int
  }

  /**
   * An `OutputPort` can be connected to a [[Sink]] with the [[FlowGraphBuilder]].
   * The `OutputPort` is also an [[OutputHandle]] which you use to define to which
   * downstream output to emit an element.
   */
  class OutputPort[In, Out] private[akka] (override private[akka] val port: Int, parent: FlexiRoute[In])
    extends JunctionOutPort[Out] with OutputHandle {

    override private[akka] def vertex = parent.vertex

    override private[akka] def portIndex: Int = port

    override def toString: String = s"OutputPort($port)"
  }

  sealed trait DemandCondition

  /**
   * Demand condition for the [[RouteLogic#State]] that will be
   * fulfilled when there are requests for elements from one specific downstream
   * output.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  final case class DemandFrom(output: OutputHandle) extends DemandCondition

  object DemandFromAny {
    def apply(outputs: immutable.Seq[OutputHandle]): DemandFromAny = new DemandFromAny(outputs: _*)
  }
  /**
   * Demand condition for the [[RouteLogic#State]] that will be
   * fulfilled when there are requests for elements from any of the given downstream
   * outputs.
   *
   * Cancelled and completed outputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  final case class DemandFromAny(outputs: OutputHandle*) extends DemandCondition

  object DemandFromAll {
    def apply(outputs: immutable.Seq[OutputHandle]): DemandFromAll = new DemandFromAll(outputs: _*)
  }
  /**
   * Demand condition for the [[RouteLogic#State]] that will be
   * fulfilled when there are requests for elements from all of the given downstream
   * outputs.
   *
   * Cancelled and completed outputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  final case class DemandFromAll(outputs: OutputHandle*) extends DemandCondition

  /**
   * The possibly stateful logic that reads from the input and enables emitting to downstream
   * via the defined [[State]]. Handles completion, error and cancel via the defined
   * [[CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiRoute#createRouteLogic]].
   */
  abstract class RouteLogic[In] {
    def outputHandles(outputCount: Int): immutable.IndexedSeq[OutputHandle]
    def initialState: State[_]
    def initialCompletionHandling: CompletionHandling = defaultCompletionHandling

    /**
     * Context that is passed to the functions of [[State]] and [[CompletionHandling]].
     * The context provides means for performing side effects, such as emitting elements
     * downstream.
     */
    trait RouteLogicContext[Out] {
      /**
       * @return `true` if at least one element has been requested by the given downstream (output).
       */
      def isDemandAvailable(output: OutputHandle): Boolean

      /**
       * Emit one element downstream. It is only allowed to `emit` when
       * [[#isDemandAvailable]] is `true` for the given `output`, otherwise
       * `IllegalArgumentException` is thrown.
       */
      def emit(output: OutputHandle, elem: Out): Unit

      /**
       * Complete the given downstream successfully.
       */
      def complete(output: OutputHandle): Unit

      /**
       * Complete all downstreams successfully and cancel upstream.
       */
      def complete(): Unit

      /**
       * Complete the given downstream with failure.
       */
      def error(output: OutputHandle, cause: Throwable): Unit

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
    sealed case class State[Out](val condition: DemandCondition)(
      val onInput: (RouteLogicContext[Out], OutputHandle, In) ⇒ State[_])

    /**
     * Return this from [[State]] `onInput` to use same state for next element.
     */
    def SameState[In]: State[In] = sameStateInstance.asInstanceOf[State[In]]

    private val sameStateInstance = new State[Any](DemandFromAny(Nil))((_, _, _) ⇒
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
     * It returns next behavior or [[#SameState]] to keep current behavior.
     *
     * The `onError` function is called when the upstream input was completed with failure.
     * It returns next behavior or [[#SameState]] to keep current behavior.
     *
     * The `onCancel` function is called when a downstream output cancels.
     * It returns next behavior or [[#SameState]] to keep current behavior.
     */
    sealed case class CompletionHandling(
      onComplete: RouteLogicContext[Any] ⇒ Unit,
      onError: (RouteLogicContext[Any], Throwable) ⇒ Unit,
      onCancel: (RouteLogicContext[Any], OutputHandle) ⇒ State[_])

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
 * Note that a `FlexiRoute` with a specific name can only be used at one place (one vertex)
 * in the `FlowGraph`. If the `name` is not specified the `FlexiRoute` instance can only
 * be used at one place (one vertex) in the `FlowGraph`.
 *
 * @param name optional name of the junction in the [[FlowGraph]],
 */
abstract class FlexiRoute[In](override val attributes: OperationAttributes) extends RouteLogicFactory[In] {
  import FlexiRoute._

  def this(name: String) = this(OperationAttributes.name(name))
  def this() = this(OperationAttributes.none)

  private var outputCount = 0

  // hide the internal vertex things from subclass, and make it possible to create new instance
  private class RouteVertex(override val attributes: OperationAttributes) extends FlowGraphInternal.InternalVertex {
    override def minimumInputCount = 1
    override def maximumInputCount = 1
    override def minimumOutputCount = 2
    override def maximumOutputCount = outputCount

    override private[akka] val astNode = Ast.FlexiRouteNode(FlexiRoute.this.asInstanceOf[FlexiRoute[Any]], flexiRoute and attributes)

    final override private[scaladsl] def newInstance() = new RouteVertex(OperationAttributes.none)
  }

  private[scaladsl] val vertex: FlowGraphInternal.InternalVertex = new RouteVertex(attributes)

  /**
   * Input port of the `FlexiRoute` junction. A [[Source]] can be connected to this output
   * with the [[FlowGraphBuilder]].
   */
  val in: JunctionInPort[In] = new JunctionInPort[In] {
    override type NextT = Nothing
    override private[akka] def next = NoNext
    override private[akka] def vertex = FlexiRoute.this.vertex
  }

  /**
   * Concrete subclass is supposed to define one or more output ports and
   * they are created by calling this method. Each [[FlexiRoute.OutputPort]] can be
   * connected to a [[Sink]] with the [[FlowGraphBuilder]].
   * The `OutputPort` is also an [[FlexiRoute.OutputHandle]] which you use to define to which
   * downstream output to emit an element.
   */
  protected final def createOutputPort[T](): OutputPort[In, T] = {
    val port = outputCount
    outputCount += 1
    new OutputPort(port, parent = this)
  }

  /**
   * Create the stateful logic that will be used when reading input elements
   * and emitting output elements. Create a new instance every time.
   */
  override def createRouteLogic(): RouteLogic[In]

  override def toString = attributes.nameLifted match {
    case Some(n) ⇒ n
    case None    ⇒ getClass.getSimpleName + "@" + Integer.toHexString(super.hashCode())
  }
}
