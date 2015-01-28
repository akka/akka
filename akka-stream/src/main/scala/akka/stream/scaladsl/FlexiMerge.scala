/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Junctions.FlexiMergeModule
import akka.stream.scaladsl.FlexiMerge.MergeLogic
import akka.stream.{ Inlet, Shape, InPort, Graph }
import scala.collection.immutable
import scala.collection.immutable.Seq
import akka.stream.impl.StreamLayout

object FlexiMerge {

  sealed trait ReadCondition[T]

  /**
   * Read condition for the [[MergeLogic#State]] that will be
   * fulfilled when there are elements for one specific upstream
   * input.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  final case class Read[T](input: Inlet[T]) extends ReadCondition[T]

  object ReadAny {
    def apply[T](inputs: immutable.Seq[Inlet[T]]): ReadAny[T] = new ReadAny(inputs: _*)
    def apply(p: Shape): ReadAny[Any] = new ReadAny(p.inlets.asInstanceOf[Seq[Inlet[Any]]]: _*)
  }

  /**
   * Read condition for the [[MergeLogic#State]] that will be
   * fulfilled when there are elements for any of the given upstream
   * inputs.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `inputs`.
   */
  final case class ReadAny[T](inputs: Inlet[T]*) extends ReadCondition[T]

  object ReadPreferred {
    def apply[T](preferred: Inlet[T], secondaries: immutable.Seq[Inlet[T]]): ReadPreferred[T] =
      new ReadPreferred(preferred, secondaries: _*)
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
  final case class ReadPreferred[T](preferred: Inlet[T], secondaries: Inlet[T]*) extends ReadCondition[T]

  object ReadAll {
    def apply[T](inputs: immutable.Seq[Inlet[T]]): ReadAll[T] = new ReadAll(new ReadAllInputs(_), inputs: _*)
    def apply[T](inputs: Inlet[T]*): ReadAll[T] = new ReadAll(new ReadAllInputs(_), inputs: _*)
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
  final case class ReadAll[T](mkResult: immutable.Map[InPort, Any] ⇒ ReadAllInputsBase, inputs: Inlet[T]*) extends ReadCondition[ReadAllInputs]

  /** INTERNAL API */
  private[stream] trait ReadAllInputsBase

  /**
   * Provides typesafe accessors to values from inputs supplied to [[ReadAll]].
   */
  final class ReadAllInputs(map: immutable.Map[InPort, Any]) extends ReadAllInputsBase {
    def apply[T](input: Inlet[T]): T = map(input).asInstanceOf[T]
    def get[T](input: Inlet[T]): Option[T] = map.get(input).asInstanceOf[Option[T]]
    def getOrElse[T](input: Inlet[T], default: ⇒ T): T = map.getOrElse(input, default).asInstanceOf[T]
  }

  /**
   * The possibly stateful logic that reads from input via the defined [[MergeLogic#State]] and
   * handles completion and error via the defined [[MergeLogic#CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiMerge#createMergeLogic]].
   */
  abstract class MergeLogic[Out] {

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
      def cancel(input: InPort): Unit

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
    sealed case class State[In](condition: ReadCondition[In])(
      val onInput: (MergeLogicContext, InPort, In) ⇒ State[_])

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
      onComplete: (MergeLogicContext, InPort) ⇒ State[_],
      onError: (MergeLogicContext, InPort, Throwable) ⇒ State[_])

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
 * Note that a `FlexiMerge` instance can only be used at one place in the `FlowGraph` (one vertex).
 *
 * @param ports ports that this junction exposes
 * @param attributes optional attributes for this vertex
 */
abstract class FlexiMerge[Out, S <: Shape](val shape: S, attributes: OperationAttributes) extends Graph[S, Unit] {
  val module: StreamLayout.Module = new FlexiMergeModule(shape, createMergeLogic)

  type PortT = S

  def createMergeLogic(s: S): MergeLogic[Out]

  override def toString = attributes.nameLifted match {
    case Some(n) ⇒ n
    case None    ⇒ super.toString
  }
}
