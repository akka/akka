/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import scala.annotation.varargs
import akka.stream.scaladsl
import scala.collection.immutable
import java.util.{ List ⇒ JList }
import akka.japi.Util.immutableIndexedSeq
import akka.stream.impl.Ast.Defaults._
import akka.stream.impl.FlexiRouteImpl.RouteLogicFactory

object FlexiRoute {

  /**
   * @see [[OutputPort]]
   */
  sealed trait OutputHandle extends scaladsl.FlexiRoute.OutputHandle

  /**
   * An `OutputPort` can be connected to a [[Sink]] with the [[FlowGraphBuilder]].
   * The `OutputPort` is also an [[OutputHandle]] which you use to define to which
   * downstream output to emit an element.
   */
  class OutputPort[In, Out] private[akka] (val port: Int, parent: FlexiRoute[In, _])
    extends JunctionOutPort[Out] with OutputHandle {

    def handle: OutputHandle = this

    override val asScala: scaladsl.JunctionOutPort[Out] = new scaladsl.JunctionOutPort[Out] {
      override def port: Int = OutputPort.this.port
      override def vertex = parent.vertex
    }

    /**
     * INTERNAL API
     */
    override private[akka] def portIndex: Int = port

    override def toString: String = s"OutputPort($port)"
  }

  sealed trait DemandCondition

  /**
   * Demand condition for the [[State]] that will be
   * fulfilled when there are requests for elements from one specific downstream
   * output.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  class DemandFrom(val output: OutputHandle) extends DemandCondition

  /**
   * Demand condition for the [[State]] that will be
   * fulfilled when there are requests for elements from any of the given downstream
   * outputs.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  class DemandFromAny(val outputs: JList[OutputHandle]) extends DemandCondition

  /**
   * Demand condition for the [[State]] that will be
   * fulfilled when there are requests for elements from all of the given downstream
   * outputs.
   *
   * Cancelled and completed outputs are not used, i.e. it is allowed
   * to specify them in the list of `outputs`.
   */
  class DemandFromAll(val outputs: JList[OutputHandle]) extends DemandCondition

  /**
   * Context that is passed to the functions of [[State]] and [[CompletionHandling]].
   * The context provides means for performing side effects, such as emitting elements
   * downstream.
   */
  trait RouteLogicContext[In, Out] {
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
    def changeCompletionHandling(completion: CompletionHandling[In]): Unit
  }

  /**
   * How to handle completion or error from upstream input and how to
   * handle cancel from downstream output.
   *
   * The `onComplete` method is called the upstream input was completed successfully.
   * It returns next behavior or [[#sameState]] to keep current behavior.
   *
   * The `onError` method is called when the upstream input was completed with failure.
   * It returns next behavior or [[#SameState]] to keep current behavior.
   *
   * The `onCancel` method is called when a downstream output cancels.
   * It returns next behavior or [[#sameState]] to keep current behavior.
   */
  abstract class CompletionHandling[In] {
    def onComplete(ctx: RouteLogicContext[In, Any]): Unit
    def onError(ctx: RouteLogicContext[In, Any], cause: Throwable): Unit
    def onCancel(ctx: RouteLogicContext[In, Any], output: OutputHandle): State[In, _]
  }

  /**
   * Definition of which outputs that must have requested elements and how to act
   * on the read elements. When an element has been read [[#onInput]] is called and
   * then it is ensured that the specified downstream outputs have requested at least
   * one element, i.e. it is allowed to emit at least one element downstream with
   * [[RouteLogicContext#emit]].
   *
   * The `onInput` method is called when an `element` was read from upstream.
   * The function returns next behavior or [[#sameState]] to keep current behavior.
   */
  abstract class State[In, Out](val condition: DemandCondition) {
    def onInput(ctx: RouteLogicContext[In, Out], preferredOutput: OutputHandle, element: In): State[In, _]
  }

  /**
   * The possibly stateful logic that reads from the input and enables emitting to downstream
   * via the defined [[State]]. Handles completion, error and cancel via the defined
   * [[CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiRoute#createRouteLogic]].
   */
  abstract class RouteLogic[In, Out] {
    def outputHandles(outputCount: Int): JList[OutputHandle]
    def initialState: State[In, Out]
    def initialCompletionHandling: CompletionHandling[In] = defaultCompletionHandling

    /**
     * Return this from [[State]] `onInput` to use same state for next element.
     */
    def sameState[A]: State[In, A] = FlexiRoute.sameStateInstance.asInstanceOf[State[In, A]]

    /**
     * Convenience to create a [[DemandFromAny]] condition.
     */
    @varargs def demandFromAny(outputs: OutputHandle*): DemandFromAny = {
      import scala.collection.JavaConverters._
      new DemandFromAny(outputs.asJava)
    }

    /**
     * Convenience to create a [[DemandFromAll]] condition.
     */
    @varargs def demandFromAll(outputs: OutputHandle*): DemandFromAll = {
      import scala.collection.JavaConverters._
      new DemandFromAll(outputs.asJava)
    }

    /**
     * Convenience to create a [[DemandFrom]] condition.
     */
    def demandFrom(output: OutputHandle): DemandFrom = new DemandFrom(output)

    /**
     * When an output cancels it continues with remaining outputs.
     * Error or completion from upstream are immediately propagated.
     */
    def defaultCompletionHandling: CompletionHandling[In] =
      new CompletionHandling[In] {
        override def onComplete(ctx: RouteLogicContext[In, Any]): Unit = ()
        override def onError(ctx: RouteLogicContext[In, Any], cause: Throwable): Unit = ()
        override def onCancel(ctx: RouteLogicContext[In, Any], output: OutputHandle): State[In, _] =
          sameState
      }

    /**
     * Completes as soon as any output cancels.
     * Error or completion from upstream are immediately propagated.
     */
    def eagerClose[A]: CompletionHandling[In] =
      new CompletionHandling[In] {
        override def onComplete(ctx: RouteLogicContext[In, Any]): Unit = ()
        override def onError(ctx: RouteLogicContext[In, Any], cause: Throwable): Unit = ()
        override def onCancel(ctx: RouteLogicContext[In, Any], output: OutputHandle): State[In, _] = {
          ctx.complete()
          sameState
        }
      }
  }

  private val sameStateInstance = new State[Any, Any](new DemandFromAny(java.util.Collections.emptyList[OutputHandle])) {
    override def onInput(ctx: RouteLogicContext[Any, Any], output: OutputHandle, element: Any): State[Any, Any] =
      throw new UnsupportedOperationException("SameState.onInput should not be called")

    override def toString: String = "SameState"
  }

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    class RouteLogicWrapper[In](delegate: RouteLogic[In, _]) extends scaladsl.FlexiRoute.RouteLogic[In] {
      override def outputHandles(outputCount: Int): immutable.IndexedSeq[scaladsl.FlexiRoute.OutputHandle] =
        immutableIndexedSeq(delegate.outputHandles(outputCount))

      override def initialState: this.State[_] = wrapState(delegate.initialState)

      override def initialCompletionHandling: this.CompletionHandling =
        wrapCompletionHandling(delegate.initialCompletionHandling)

      private def wrapState[Out](delegateState: FlexiRoute.State[In, Out]): State[Out] =
        if (sameStateInstance == delegateState)
          SameState
        else
          State(convertDemandCondition(delegateState.condition)) { (ctx, outputHandle, elem) ⇒
            val newDelegateState =
              delegateState.onInput(new RouteLogicContextWrapper(ctx), asJava(outputHandle), elem)
            wrapState(newDelegateState)
          }

      private def wrapCompletionHandling[Out](
        delegateCompletionHandling: FlexiRoute.CompletionHandling[In]): CompletionHandling =
        CompletionHandling(
          onComplete = ctx ⇒ {
            delegateCompletionHandling.onComplete(new RouteLogicContextWrapper(ctx))
          },
          onError = (ctx, cause) ⇒ {
            delegateCompletionHandling.onError(new RouteLogicContextWrapper(ctx), cause)
          },
          onCancel = (ctx, outputHandle) ⇒ {
            val newDelegateState = delegateCompletionHandling.onCancel(
              new RouteLogicContextWrapper(ctx), asJava(outputHandle))
            wrapState(newDelegateState)
          })

      private def asJava(outputHandle: scaladsl.FlexiRoute.OutputHandle): OutputHandle =
        outputHandle.asInstanceOf[OutputHandle]

      class RouteLogicContextWrapper[Out](delegate: RouteLogicContext[Out]) extends FlexiRoute.RouteLogicContext[In, Out] {
        override def isDemandAvailable(output: OutputHandle): Boolean = delegate.isDemandAvailable(output)
        override def emit(output: OutputHandle, elem: Out): Unit = delegate.emit(output, elem)
        override def complete(): Unit = delegate.complete()
        override def complete(output: OutputHandle): Unit = delegate.complete(output)
        override def error(cause: Throwable): Unit = delegate.error(cause)
        override def error(output: OutputHandle, cause: Throwable): Unit = delegate.error(output, cause)
        override def changeCompletionHandling(completion: FlexiRoute.CompletionHandling[In]): Unit =
          delegate.changeCompletionHandling(wrapCompletionHandling(completion))
      }

    }

    def convertDemandCondition(condition: DemandCondition): scaladsl.FlexiRoute.DemandCondition =
      condition match {
        case c: DemandFromAny ⇒ scaladsl.FlexiRoute.DemandFromAny(immutableIndexedSeq(c.outputs))
        case c: DemandFromAll ⇒ scaladsl.FlexiRoute.DemandFromAll(immutableIndexedSeq(c.outputs))
        case c: DemandFrom    ⇒ scaladsl.FlexiRoute.DemandFrom(c.output)
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
 * Note that a `FlexiRoute` with a specific name can only be used at one place (one vertex)
 * in the `FlowGraph`. If the `name` is not specified the `FlexiRoute` instance can only
 * be used at one place (one vertex) in the `FlowGraph`.
 *
 * @param name optional name of the junction in the [[FlowGraph]],
 */
abstract class FlexiRoute[In, Out](val attributes: OperationAttributes) {
  import FlexiRoute._
  import scaladsl.FlowGraphInternal
  import akka.stream.impl.Ast

  def this(name: String) = this(OperationAttributes.name(name))
  def this() = this(OperationAttributes.none)

  private var outputCount = 0

  // hide the internal vertex things from subclass, and make it possible to create new instance
  private class RouteVertex(override val attributes: scaladsl.OperationAttributes) extends FlowGraphInternal.InternalVertex {
    override def minimumInputCount = 1
    override def maximumInputCount = 1
    override def minimumOutputCount = 2
    override def maximumOutputCount = outputCount

    override private[akka] val astNode = {
      val factory = new RouteLogicFactory[Any] {
        override def attributes: scaladsl.OperationAttributes = RouteVertex.this.attributes
        override def createRouteLogic(): scaladsl.FlexiRoute.RouteLogic[Any] =
          new Internal.RouteLogicWrapper(FlexiRoute.this.createRouteLogic().asInstanceOf[RouteLogic[Any, Any]])
      }
      Ast.FlexiRouteNode(factory, flexiRoute and attributes)
    }

    final override def newInstance() = new RouteVertex(attributes.withoutName)
  }

  /**
   * INTERNAL API
   */
  private[akka] val vertex: FlowGraphInternal.InternalVertex = new RouteVertex(attributes.asScala)

  /**
   * Input port of the `FlexiRoute` junction. A [[Source]] can be connected to this output
   * with the [[FlowGraphBuilder]].
   */
  val in: JunctionInPort[In] = new JunctionInPort[In] {
    override val asScala: scaladsl.JunctionInPort[In] = new scaladsl.JunctionInPort[In] {
      override def vertex = FlexiRoute.this.vertex
      type NextT = Nothing
      override def next = scaladsl.NoNext
    }
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
  def createRouteLogic(): RouteLogic[In, Out]

  override def toString = attributes.asScala.nameLifted match {
    case Some(n) ⇒ n
    case None    ⇒ getClass.getSimpleName + "@" + Integer.toHexString(super.hashCode())
  }

}
