/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.annotation.varargs
import scala.collection.immutable
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.impl.Ast
import akka.stream.impl.Ast.Defaults._
import akka.stream.impl.FlexiMergeImpl.MergeLogicFactory

object FlexiMerge {

  /**
   * @see [[InputPort]]
   */
  trait InputHandle {
    private[akka] def portIndex: Int
  }

  /**
   * An `InputPort` can be connected to a [[Source]] with the [[FlowGraphBuilder]].
   * The `InputPort` is also an [[InputHandle]], which is passed as parameter
   * to [[MergeLogic#State]] `onInput` when an input element has been read so that you
   * can know exactly from which input the element was read.
   */
  class InputPort[In, Out] private[akka] (override private[akka] val port: Int, parent: FlexiMerge[Out])
    extends JunctionInPort[In] with InputHandle {
    type NextT = Out
    override private[akka] def next = parent.out
    override private[akka] def vertex = parent.vertex

    override private[akka] def portIndex: Int = port

    override def toString: String = s"InputPort($port)"
  }

  sealed trait ReadCondition
  /**
   * Read condition for the [[MergeLogic#State]] that will be
   * fulfilled when there are elements for one specific upstream
   * input.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  final case class Read(input: InputHandle) extends ReadCondition

  object ReadAny {
    def apply(inputs: immutable.Seq[InputHandle]): ReadAny = new ReadAny(inputs: _*)
  }
  /**
   * Read condition for the [[MergeLogic#State]] that will be
   * fulfilled when there are elements for any of the given upstream
   * inputs.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `inputs`.
   */
  final case class ReadAny(inputs: InputHandle*) extends ReadCondition

  object ReadPreferred {
    def apply(preferred: InputHandle)(secondaries: InputHandle*): ReadPreferred =
      new ReadPreferred(preferred, secondaries.toArray)

    def apply(preferred: InputHandle, secondaries: immutable.Seq[InputHandle]): ReadPreferred =
      new ReadPreferred(preferred, secondaries.toArray)
  }
  /**
   * Read condition for the [[MergeLogic#State]] that will be
   * fulfilled when there are elements for any of the given upstream
   * inputs, however it differs from [[ReadAny]] in the case that both
   * the `preferred` and at least one other `secondary` input have demand,
   * the `preferred` input will always be consumed first.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `inputs`.
   */
  final case class ReadPreferred(preferred: InputHandle, secondaries: Array[InputHandle]) extends ReadCondition

  object ReadAll {
    def apply(inputs: immutable.Seq[InputHandle]): ReadAll = new ReadAll(ReadAllInputs, inputs: _*)
    def apply(inputs: InputHandle*): ReadAll = new ReadAll(ReadAllInputs, inputs: _*)
  }
  /**
   * Read condition for the [[MergeLogic#State]] that will be
   * fulfilled when there are elements for *all* of the given upstream
   * inputs.
   *
   * The emited element the will be a [[ReadAllInputs]] object, which contains values for all non-cancelled inputs of this FlexiMerge.
   *
   * Cancelled inputs are not used, i.e. it is allowed to specify them in the list of `inputs`,
   * the resulting [[ReadAllInputs]] will then not contain values for this element, which can be
   * handled via supplying a default value instead of the value from the (now cancelled) input.
   */
  final case class ReadAll(mkResult: immutable.Map[InputHandle, Any] ⇒ ReadAllInputsBase, inputs: InputHandle*) extends ReadCondition
  /** INTERNAL API */
  private[stream] trait ReadAllInputsBase
  /**
   * Provides typesafe accessors to values from inputs supplied to [[ReadAll]].
   */
  final case class ReadAllInputs(map: immutable.Map[InputHandle, Any]) extends ReadAllInputsBase {
    def apply[T](input: InputPort[T, _]): T = map(input).asInstanceOf[T]
    def get[T](input: InputPort[T, _]): Option[T] = map.get(input).asInstanceOf[Option[T]]
    def getOrElse[T, B >: T](input: InputPort[T, _], default: ⇒ B): T = map.getOrElse(input, default).asInstanceOf[T]
  }

  /**
   * The possibly stateful logic that reads from input via the defined [[MergeLogic#State]] and
   * handles completion and error via the defined [[MergeLogic#CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiMerge#createMergeLogic]].
   */
  abstract class MergeLogic[Out] {
    def inputHandles(inputCount: Int): immutable.IndexedSeq[InputHandle]
    def initialState: State[_]
    def initialCompletionHandling: CompletionHandling = defaultCompletionHandling

    /**
     * Context that is passed to the functions of [[State]] and [[CompletionHandling]].
     * The context provides means for performing side effects, such as emitting elements
     * downstream.
     */
    trait MergeLogicContext {
      /**
       * @return `true` if at least one element has been requested by downstream (output).
       */
      def isDemandAvailable: Boolean

      /**
       * Emit one element downstream. It is only allowed to `emit` when
       * [[#isDemandAvailable]] is `true`, otherwise `IllegalArgumentException`
       * is thrown.
       */
      def emit(elem: Out): Unit

      /**
       * Complete this stream successfully. Upstream subscriptions will be cancelled.
       */
      def complete(): Unit

      /**
       * Complete this stream with failure. Upstream subscriptions will be cancelled.
       */
      def error(cause: Throwable): Unit

      /**
       * Cancel a specific upstream input stream.
       */
      def cancel(input: InputHandle): Unit

      /**
       * Replace current [[CompletionHandling]].
       */
      def changeCompletionHandling(completion: CompletionHandling): Unit
    }

    /**
     * Definition of which inputs to read from and how to act on the read elements.
     * When an element has been read [[#onInput]] is called and then it is ensured
     * that downstream has requested at least one element, i.e. it is allowed to
     * emit at least one element downstream with [[MergeLogicContext#emit]].
     *
     * The `onInput` function is called when an `element` was read from the `input`.
     * The function returns next behavior or [[#SameState]] to keep current behavior.
     */
    sealed case class State[In](val condition: ReadCondition)(
      val onInput: (MergeLogicContext, InputHandle, In) ⇒ State[_])

    /**
     * Return this from [[State]] `onInput` to use same state for next element.
     */
    def SameState[In]: State[In] = sameStateInstance.asInstanceOf[State[In]]

    private val sameStateInstance = new State[Any](ReadAny(Nil))((_, _, _) ⇒
      throw new UnsupportedOperationException("SameState.onInput should not be called")) {

      // unique instance, don't use case class
      override def equals(other: Any): Boolean = super.equals(other)
      override def hashCode: Int = super.hashCode
      override def toString: String = "SameState"
    }

    /**
     * How to handle completion or error from upstream input.
     *
     * The `onComplete` function is called when an upstream input was completed successfully.
     * It returns next behavior or [[#SameState]] to keep current behavior.
     * A completion can be propagated downstream with [[MergeLogicContext#complete]],
     * or it can be swallowed to continue with remaining inputs.
     *
     * The `onError` function is called when an upstream input was completed with failure.
     * It returns next behavior or [[#SameState]] to keep current behavior.
     * An error can be propagated downstream with [[MergeLogicContext#error]],
     * or it can be swallowed to continue with remaining inputs.
     */
    sealed case class CompletionHandling(
      onComplete: (MergeLogicContext, InputHandle) ⇒ State[_],
      onError: (MergeLogicContext, InputHandle, Throwable) ⇒ State[_])

    /**
     * Will continue to operate until a read becomes unsatisfiable, then it completes.
     * Errors are immediately propagated.
     */
    val defaultCompletionHandling: CompletionHandling = CompletionHandling(
      onComplete = (_, _) ⇒ SameState,
      onError = (ctx, _, cause) ⇒ { ctx.error(cause); SameState })

    /**
     * Completes as soon as any input completes.
     * Errors are immediately propagated.
     */
    def eagerClose: CompletionHandling = CompletionHandling(
      onComplete = (ctx, _) ⇒ { ctx.complete(); SameState },
      onError = (ctx, _, cause) ⇒ { ctx.error(cause); SameState })
  }

}

/**
 * Base class for implementing custom merge junctions.
 * Such a junction always has one [[#out]] port and one or more input ports.
 * The input ports are to be defined in the concrete subclass and are created with
 * [[#createInputPort]].
 *
 * The concrete subclass must implement [[#createMergeLogic]] to define the [[FlexiMerge#MergeLogic]]
 * that will be used when reading input elements and emitting output elements.
 * The [[FlexiMerge#MergeLogic]] instance may be stateful, but the ``FlexiMerge`` instance
 * must not hold mutable state, since it may be shared across several materialized ``FlowGraph``
 * instances.
 *
 * Note that a `FlexiMerge` with a specific name can only be used at one place (one vertex)
 * in the `FlowGraph`. If the `name` is not specified the `FlexiMerge` instance can only
 * be used at one place (one vertex) in the `FlowGraph`.
 *
 * @param name optional name of the junction in the [[FlowGraph]],
 */
abstract class FlexiMerge[Out](override val attributes: OperationAttributes) extends MergeLogicFactory[Out] {
  import FlexiMerge._

  def this(name: String) = this(OperationAttributes.name(name))
  def this() = this(OperationAttributes.none)

  private var inputCount = 0

  // hide the internal vertex things from subclass, and make it possible to create new instance
  private class FlexiMergeVertex(override val attributes: OperationAttributes) extends FlowGraphInternal.InternalVertex {
    override def minimumInputCount = 2
    override def maximumInputCount = inputCount
    override def minimumOutputCount = 1
    override def maximumOutputCount = 1

    override private[akka] val astNode = Ast.FlexiMergeNode(FlexiMerge.this.asInstanceOf[FlexiMerge[Any]], flexiMerge and attributes)

    final override private[scaladsl] def newInstance() = new FlexiMergeVertex(attributes.withoutName)
  }

  private[scaladsl] val vertex: FlowGraphInternal.InternalVertex = new FlexiMergeVertex(attributes)

  /**
   * Output port of the `FlexiMerge` junction. A [[Sink]] can be connected to this output
   * with the [[FlowGraphBuilder]].
   */
  val out: JunctionOutPort[Out] = new JunctionOutPort[Out] {
    override private[akka] def vertex = FlexiMerge.this.vertex
  }

  /**
   * Concrete subclass is supposed to define one or more input ports and
   * they are created by calling this method. Each [[FlexiMerge.InputPort]] can be
   * connected to a [[Source]] with the [[FlowGraphBuilder]].
   * The `InputPort` is also an [[FlexiMerge.InputHandle]], which is passed as parameter
   * to [[FlexiMerge#MergeLogic#State]] `onInput` when an input element has been read so that you
   * can know exactly from which input the element was read.
   */
  protected final def createInputPort[T](): InputPort[T, Out] = {
    val port = inputCount
    inputCount += 1
    new InputPort(port, parent = this)
  }

  /**
   * Create the stateful logic that will be used when reading input elements
   * and emitting output elements. Create a new instance every time.
   */
  override def createMergeLogic(): MergeLogic[Out]

  override def toString = attributes.nameLifted match {
    case Some(n) ⇒ n
    case None    ⇒ getClass.getSimpleName + "@" + Integer.toHexString(super.hashCode())
  }
}
