/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import scala.annotation.varargs
import akka.stream.scaladsl
import akka.stream.scaladsl.FlexiMerge.ReadAllInputsBase
import scala.collection.immutable
import java.util.{ List ⇒ JList }
import akka.japi.Util.immutableIndexedSeq
import akka.stream._
import akka.stream.impl.StreamLayout
import akka.stream.impl.Junctions.FlexiMergeModule

object FlexiMerge {

  sealed trait ReadCondition[T]

  /**
   * Read condition for the [[State]] that will be
   * fulfilled when there are elements for one specific upstream
   * input.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  class Read[T](val input: Inlet[T]) extends ReadCondition[T]

  /**
   * Read condition for the [[State]] that will be
   * fulfilled when there are elements for any of the given upstream
   * inputs.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `inputs`.
   */
  class ReadAny[T](val inputs: JList[InPort]) extends ReadCondition[T]

  /**
   * Read condition for the [[FlexiMerge#State]] that will be
   * fulfilled when there are elements for any of the given upstream
   * inputs, however it differs from [[ReadAny]] in the case that both
   * the `preferred` and at least one other `secondary` input have demand,
   * the `preferred` input will always be consumed first.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `inputs`.
   */
  class ReadPreferred[T](val preferred: InPort, val secondaries: JList[InPort]) extends ReadCondition[T]

  /**
   * Read condition for the [[FlexiMerge#State]] that will be
   * fulfilled when there are elements for *all* of the given upstream
   * inputs.
   *
   * The emitted element the will be a [[ReadAllInputs]] object, which contains values for all non-cancelled inputs of this FlexiMerge.
   *
   * Cancelled inputs are not used, i.e. it is allowed to specify them in the list of `inputs`,
   * the resulting [[ReadAllInputs]] will then not contain values for this element, which can be
   * handled via supplying a default value instead of the value from the (now cancelled) input.
   */
  class ReadAll(val inputs: JList[InPort]) extends ReadCondition[ReadAllInputs]

  /**
   * Provides typesafe accessors to values from inputs supplied to [[ReadAll]].
   */
  final class ReadAllInputs(map: immutable.Map[InPort, Any]) extends ReadAllInputsBase {
    /** Returns the value for the given [[Inlet]], or `null` if this input was cancelled. */
    def get[T](input: Inlet[T]): T = getOrDefault(input, null)

    /** Returns the value for the given [[Inlet]], or `defaultValue`. */
    def getOrDefault[T, B >: T](input: Inlet[T], defaultValue: B): T = map.getOrElse(input, defaultValue).asInstanceOf[T]
  }

  /**
   * Context that is passed to the `onInput` function of [[State]].
   * The context provides means for performing side effects, such as emitting elements
   * downstream.
   */
  trait MergeLogicContext[Out] extends MergeLogicContextBase[Out] {
    /**
     * Emit one element downstream. It is only allowed to `emit` zero or one
     * element in response to `onInput`, otherwise `IllegalStateException`
     * is thrown.
     */
    def emit(elem: Out): Unit
  }

  /**
   * Context that is passed to the `onInput` function of [[State]].
   * The context provides means for performing side effects, such as emitting elements
   * downstream.
   */
  trait MergeLogicContextBase[Out] {
    /**
     * Complete this stream successfully. Upstream subscriptions will be cancelled.
     */
    def finish(): Unit

    /**
     * Complete this stream with failure. Upstream subscriptions will be cancelled.
     */
    def fail(cause: Throwable): Unit

    /**
     * Cancel a specific upstream input stream.
     */
    def cancel(input: InPort): Unit

    /**
     * Replace current [[CompletionHandling]].
     */
    def changeCompletionHandling(completion: CompletionHandling[Out]): Unit
  }

  /**
   * How to handle completion or failure from upstream input.
   *
   * The `onUpstreamFinish` method is called when an upstream input was completed sucessfully.
   * It returns next behavior or [[MergeLogic#sameState]] to keep current behavior.
   * A completion can be propagated downstream with [[MergeLogicContextBase#finish]],
   * or it can be swallowed to continue with remaining inputs.
   *
   * The `onUpstreamFailure` method is called when an upstream input was completed with failure.
   * It returns next behavior or [[MergeLogic#sameState]] to keep current behavior.
   * A failure can be propagated downstream with [[MergeLogicContextBase#fail]],
   * or it can be swallowed to continue with remaining inputs.
   *
   * It is not possible to emit elements from the completion handling, since completion
   * handlers may be invoked at any time (without regard to downstream demand being available).
   */
  abstract class CompletionHandling[Out] {
    def onUpstreamFinish(ctx: MergeLogicContextBase[Out], input: InPort): State[_, Out]
    def onUpstreamFailure(ctx: MergeLogicContextBase[Out], input: InPort, cause: Throwable): State[_, Out]
  }

  /**
   * Definition of which inputs to read from and how to act on the read elements.
   * When an element has been read [[#onInput]] is called and then it is ensured
   * that downstream has requested at least one element, i.e. it is allowed to
   * emit at least one element downstream with [[MergeLogicContext#emit]].
   *
   * The `onInput` method is called when an `element` was read from the `input`.
   * The method returns next behavior or [[MergeLogic#sameState]] to keep current behavior.
   */
  abstract class State[T, Out](val condition: ReadCondition[T]) {
    def onInput(ctx: MergeLogicContext[Out], input: InPort, element: T): State[_, Out]
  }

  /**
   * The possibly stateful logic that reads from input via the defined [[State]] and
   * handles completion and failure via the defined [[CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiMerge#createMergeLogic]].
   */
  abstract class MergeLogic[T, Out] {
    def initialState: State[T, Out]
    def initialCompletionHandling: CompletionHandling[Out] = defaultCompletionHandling
    /**
     * Return this from [[State]] `onInput` to use same state for next element.
     */
    def sameState[U]: State[U, Out] = FlexiMerge.sameStateInstance.asInstanceOf[State[U, Out]]

    /**
     * Convenience to create a [[Read]] condition.
     */
    def read[U](input: Inlet[U]): Read[U] = new Read(input)

    /**
     * Convenience to create a [[ReadAny]] condition.
     */
    @varargs def readAny[U](inputs: InPort*): ReadAny[U] = {
      import scala.collection.JavaConverters._
      new ReadAny(inputs.asJava)
    }

    /**
     * Convenience to create a [[ReadPreferred]] condition.
     */
    @varargs def readPreferred[U](preferred: InPort, secondaries: InPort*): ReadPreferred[U] = {
      import scala.collection.JavaConverters._
      new ReadPreferred(preferred, secondaries.asJava)
    }

    /**
     * Convenience to create a [[ReadAll]] condition.
     */
    @varargs def readAll(inputs: InPort*): ReadAll = {
      import scala.collection.JavaConverters._
      new ReadAll(inputs.asJava)
    }

    /**
     * Will continue to operate until a read becomes unsatisfiable, then it completes.
     * Failures are immediately propagated.
     */
    def defaultCompletionHandling: CompletionHandling[Out] =
      new CompletionHandling[Out] {
        override def onUpstreamFinish(ctx: MergeLogicContextBase[Out], input: InPort): State[_, Out] =
          sameState
        override def onUpstreamFailure(ctx: MergeLogicContextBase[Out], input: InPort, cause: Throwable): State[_, Out] = {
          ctx.fail(cause)
          sameState
        }
      }

    /**
     * Completes as soon as any input completes.
     * Failures are immediately propagated.
     */
    def eagerClose: CompletionHandling[Out] =
      new CompletionHandling[Out] {
        override def onUpstreamFinish(ctx: MergeLogicContextBase[Out], input: InPort): State[_, Out] = {
          ctx.finish()
          sameState
        }
        override def onUpstreamFailure(ctx: MergeLogicContextBase[Out], input: InPort, cause: Throwable): State[_, Out] = {
          ctx.fail(cause)
          sameState
        }
      }
  }

  private val sameStateInstance = new State[AnyRef, Any](new ReadAny(java.util.Collections.emptyList[InPort])) {
    override def onInput(ctx: MergeLogicContext[Any], input: InPort, element: AnyRef): State[AnyRef, Any] =
      throw new UnsupportedOperationException("SameState.onInput should not be called")

    override def toString: String = "SameState"
  }

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    class MergeLogicWrapper[T, Out](delegate: MergeLogic[T, Out]) extends scaladsl.FlexiMerge.MergeLogic[Out] {

      override def initialState: State[T] = wrapState(delegate.initialState)

      override def initialCompletionHandling: this.CompletionHandling =
        wrapCompletionHandling(delegate.initialCompletionHandling)

      private def wrapState[U](delegateState: FlexiMerge.State[U, Out]): State[U] =
        if (sameStateInstance == delegateState)
          SameState
        else
          State(convertReadCondition(delegateState.condition)) { (ctx, inputHandle, elem) ⇒
            val newDelegateState =
              delegateState.onInput(new MergeLogicContextWrapper(ctx), inputHandle, elem)
            wrapState(newDelegateState)
          }

      private def wrapCompletionHandling(
        delegateCompletionHandling: FlexiMerge.CompletionHandling[Out]): CompletionHandling =
        CompletionHandling(
          onUpstreamFinish = (ctx, inputHandle) ⇒ {
            val newDelegateState = delegateCompletionHandling.onUpstreamFinish(
              new MergeLogicContextBaseWrapper(ctx), inputHandle)
            wrapState(newDelegateState)
          },
          onUpstreamFailure = (ctx, inputHandle, cause) ⇒ {
            val newDelegateState = delegateCompletionHandling.onUpstreamFailure(
              new MergeLogicContextBaseWrapper(ctx), inputHandle, cause)
            wrapState(newDelegateState)
          })

      class MergeLogicContextWrapper(delegate: MergeLogicContext)
        extends MergeLogicContextBaseWrapper(delegate) with FlexiMerge.MergeLogicContext[Out] {
        override def emit(elem: Out): Unit = delegate.emit(elem)
      }
      class MergeLogicContextBaseWrapper(delegate: MergeLogicContextBase) extends FlexiMerge.MergeLogicContextBase[Out] {
        override def finish(): Unit = delegate.finish()
        override def fail(cause: Throwable): Unit = delegate.fail(cause)
        override def cancel(input: InPort): Unit = delegate.cancel(input)
        override def changeCompletionHandling(completion: FlexiMerge.CompletionHandling[Out]): Unit =
          delegate.changeCompletionHandling(wrapCompletionHandling(completion))
      }

    }

    private def toSeq[T](l: JList[InPort]) = immutableIndexedSeq(l).asInstanceOf[immutable.Seq[Inlet[T]]]

    def convertReadCondition[T](condition: ReadCondition[T]): scaladsl.FlexiMerge.ReadCondition[T] = {
      condition match {
        case r: ReadAny[_]       ⇒ scaladsl.FlexiMerge.ReadAny(toSeq[T](r.inputs))
        case r: ReadPreferred[_] ⇒ scaladsl.FlexiMerge.ReadPreferred(r.preferred.asInstanceOf[Inlet[T]], toSeq[T](r.secondaries))
        case r: Read[_]          ⇒ scaladsl.FlexiMerge.Read(r.input)
        case r: ReadAll          ⇒ scaladsl.FlexiMerge.ReadAll(new ReadAllInputs(_), toSeq[AnyRef](r.inputs): _*).asInstanceOf[scaladsl.FlexiMerge.ReadCondition[ReadAllInputs]]
      }
    }

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
 * As response to an input element it is allowed to emit at most one output element.
 *
 * The [[FlexiMerge#MergeLogic]] instance may be stateful, but the ``FlexiMerge`` instance
 * must not hold mutable state, since it may be shared across several materialized ``FlowGraph``
 * instances.
 *
 * Note that a `FlexiMerge` instance can only be used at one place in the `FlowGraph` (one vertex).
 *
 * @param attributes optional attributes for this vertex
 */
abstract class FlexiMerge[T, Out, S <: Shape](val shape: S, val attributes: OperationAttributes) extends Graph[S, Unit] {
  import FlexiMerge._

  val module: StreamLayout.Module = new FlexiMergeModule(shape, (s: S) ⇒ new Internal.MergeLogicWrapper(createMergeLogic(s)))

  def createMergeLogic(s: S): MergeLogic[T, Out]

  override def toString = attributes.nameLifted match {
    case Some(n) ⇒ n
    case None    ⇒ super.toString
  }
}
