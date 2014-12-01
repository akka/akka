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
import akka.stream.impl.Ast.Defaults._
import akka.stream.impl.FlexiMergeImpl.MergeLogicFactory

object FlexiMerge {

  /**
   * @see [[InputPort]]
   */
  sealed trait InputHandle extends scaladsl.FlexiMerge.InputHandle

  /**
   * An `InputPort` can be connected to a [[Source]] with the [[FlowGraphBuilder]].
   * The `InputPort` is also an [[InputHandle]], which is passed as parameter
   * to [[State]] `onInput` when an input element has been read so that you
   * can know exactly from which input the element was read.
   */
  class InputPort[In, Out] private[akka] (val port: Int, parent: FlexiMerge[_, Out])
    extends JunctionInPort[In] with InputHandle {

    def handle: InputHandle = this

    override val asScala: scaladsl.JunctionInPort[In] = new scaladsl.JunctionInPort[In] {
      override def port: Int = InputPort.this.port
      override def vertex = parent.vertex
      type NextT = Nothing
      override def next = scaladsl.NoNext
    }

    /**
     * INTERNAL API
     */
    override private[akka] def portIndex: Int = port

    override def toString: String = s"InputPort($port)"
  }

  sealed trait ReadCondition

  /**
   * Read condition for the [[State]] that will be
   * fulfilled when there are elements for one specific upstream
   * input.
   *
   * It is not allowed to use a handle that has been cancelled or
   * has been completed. `IllegalArgumentException` is thrown if
   * that is not obeyed.
   */
  class Read(val input: InputHandle) extends ReadCondition

  /**
   * Read condition for the [[State]] that will be
   * fulfilled when there are elements for any of the given upstream
   * inputs.
   *
   * Cancelled and completed inputs are not used, i.e. it is allowed
   * to specify them in the list of `inputs`.
   */
  class ReadAny(val inputs: JList[InputHandle]) extends ReadCondition

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
  class ReadPreferred(val preferred: InputHandle, val secondaries: JList[InputHandle]) extends ReadCondition

  /**
   * Read condition for the [[FlexiMerge#State]] that will be
   * fulfilled when there are elements for *all* of the given upstream
   * inputs.
   *
   * The emited element the will be a [[ReadAllInputs]] object, which contains values for all non-cancelled inputs of this FlexiMerge.
   *
   * Cancelled inputs are not used, i.e. it is allowed to specify them in the list of `inputs`,
   * the resulting [[ReadAllInputs]] will then not contain values for this element, which can be
   * handled via supplying a default value instead of the value from the (now cancelled) input.
   */
  class ReadAll(val inputs: JList[InputHandle]) extends ReadCondition

  /**
   * Provides typesafe accessors to values from inputs supplied to [[ReadAll]].
   */
  final class ReadAllInputs(map: immutable.Map[scaladsl.FlexiMerge.InputHandle, Any]) extends ReadAllInputsBase {
    /** Returns the value for the given [[InputPort]], or `null` if this input was cancelled. */
    def get[T](input: InputPort[T, _]): T = getOrDefault(input, null)

    /** Returns the value for the given [[InputPort]], or `defaultValue`. */
    def getOrDefault[T, B >: T](input: InputPort[T, _], defaultValue: B): T = map.getOrElse(input, defaultValue).asInstanceOf[T]
  }

  /**
   * Context that is passed to the methods of [[State]] and [[CompletionHandling]].
   * The context provides means for performing side effects, such as emitting elements
   * downstream.
   */
  trait MergeLogicContext[Out] {
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
     * Complete this stream succesfully. Upstream subscriptions will be cancelled.
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
    def changeCompletionHandling(completion: CompletionHandling[Out]): Unit
  }

  /**
   * How to handle completion or error from upstream input.
   *
   * The `onComplete` method is called when an upstream input was completed sucessfully.
   * It returns next behavior or [[MergeLogic#sameState]] to keep current behavior.
   * A completion can be propagated downstream with [[MergeLogicContext#complete]],
   * or it can be swallowed to continue with remaining inputs.
   *
   * The `onError` method is called when an upstream input was completed sucessfully.
   * It returns next behavior or [[MergeLogic#sameState]] to keep current behavior.
   * An error can be propagated downstream with [[MergeLogicContext#error]],
   * or it can be swallowed to continue with remaining inputs.
   */
  abstract class CompletionHandling[Out] {
    def onComplete(ctx: MergeLogicContext[Out], input: InputHandle): State[_, Out]
    def onError(ctx: MergeLogicContext[Out], input: InputHandle, cause: Throwable): State[_, Out]
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
  abstract class State[In, Out](val condition: ReadCondition) {
    def onInput(ctx: MergeLogicContext[Out], input: InputHandle, element: In): State[_, Out]
  }

  /**
   * The possibly stateful logic that reads from input via the defined [[State]] and
   * handles completion and error via the defined [[CompletionHandling]].
   *
   * Concrete instance is supposed to be created by implementing [[FlexiMerge#createMergeLogic]].
   */
  abstract class MergeLogic[In, Out] {
    def inputHandles(inputCount: Int): JList[InputHandle]
    def initialState: State[In, Out]
    def initialCompletionHandling: CompletionHandling[Out] = defaultCompletionHandling
    /**
     * Return this from [[State]] `onInput` to use same state for next element.
     */
    def sameState[A]: State[A, Out] = FlexiMerge.sameStateInstance.asInstanceOf[State[A, Out]]

    /**
     * Convenience to create a [[Read]] condition.
     */
    def read(input: InputHandle): Read = new Read(input)

    /**
     * Convenience to create a [[ReadAny]] condition.
     */
    @varargs def readAny(inputs: InputHandle*): ReadAny = {
      import scala.collection.JavaConverters._
      new ReadAny(inputs.asJava)
    }

    /**
     * Convenience to create a [[ReadPreferred]] condition.
     */
    @varargs def readPreferred(preferred: InputHandle, secondaries: InputHandle*): ReadPreferred = {
      import scala.collection.JavaConverters._
      new ReadPreferred(preferred, secondaries.asJava)
    }

    /**
     * Convenience to create a [[ReadAll]] condition.
     */
    @varargs def readAll(inputs: InputHandle*): ReadAll = {
      import scala.collection.JavaConverters._
      new ReadAll(inputs.asJava)
    }

    /**
     * Will continue to operate until a read becomes unsatisfiable, then it completes.
     * Errors are immediately propagated.
     */
    def defaultCompletionHandling[A]: CompletionHandling[Out] =
      new CompletionHandling[Out] {
        override def onComplete(ctx: MergeLogicContext[Out], input: InputHandle): State[A, Out] =
          sameState
        override def onError(ctx: MergeLogicContext[Out], input: InputHandle, cause: Throwable): State[A, Out] = {
          ctx.error(cause)
          sameState
        }
      }

    /**
     * Completes as soon as any input completes.
     * Errors are immediately propagated.
     */
    def eagerClose[A]: CompletionHandling[Out] =
      new CompletionHandling[Out] {
        override def onComplete(ctx: MergeLogicContext[Out], input: InputHandle): State[A, Out] = {
          ctx.complete()
          sameState
        }
        override def onError(ctx: MergeLogicContext[Out], input: InputHandle, cause: Throwable): State[A, Out] = {
          ctx.error(cause)
          sameState
        }
      }
  }

  private val sameStateInstance = new State[Any, Any](new ReadAny(java.util.Collections.emptyList[InputHandle])) {
    override def onInput(ctx: MergeLogicContext[Any], input: InputHandle, element: Any): State[Any, Any] =
      throw new UnsupportedOperationException("SameState.onInput should not be called")

    override def toString: String = "SameState"
  }

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    class MergeLogicWrapper[Out](delegate: MergeLogic[_, Out]) extends scaladsl.FlexiMerge.MergeLogic[Out] {
      override def inputHandles(inputCount: Int): immutable.IndexedSeq[scaladsl.FlexiMerge.InputHandle] =
        immutableIndexedSeq(delegate.inputHandles(inputCount))

      override def initialState: this.State[_] = wrapState(delegate.initialState)

      override def initialCompletionHandling: this.CompletionHandling =
        wrapCompletionHandling(delegate.initialCompletionHandling)

      private def wrapState[In](delegateState: FlexiMerge.State[In, Out]): State[In] =
        if (sameStateInstance == delegateState)
          SameState
        else
          State(convertReadCondition(delegateState.condition)) { (ctx, inputHandle, elem) ⇒
            val newDelegateState =
              delegateState.onInput(new MergeLogicContextWrapper(ctx), asJava(inputHandle), elem)
            wrapState(newDelegateState)
          }

      private def wrapCompletionHandling(
        delegateCompletionHandling: FlexiMerge.CompletionHandling[Out]): CompletionHandling =
        CompletionHandling(
          onComplete = (ctx, inputHandle) ⇒ {
            val newDelegateState = delegateCompletionHandling.onComplete(
              new MergeLogicContextWrapper(ctx), asJava(inputHandle))
            wrapState(newDelegateState)
          },
          onError = (ctx, inputHandle, cause) ⇒ {
            val newDelegateState = delegateCompletionHandling.onError(
              new MergeLogicContextWrapper(ctx), asJava(inputHandle), cause)
            wrapState(newDelegateState)
          })

      private def asJava(inputHandle: scaladsl.FlexiMerge.InputHandle): InputHandle =
        inputHandle.asInstanceOf[InputHandle]

      class MergeLogicContextWrapper[In](delegate: MergeLogicContext) extends FlexiMerge.MergeLogicContext[Out] {
        override def isDemandAvailable: Boolean = delegate.isDemandAvailable
        override def emit(elem: Out): Unit = delegate.emit(elem)
        override def complete(): Unit = delegate.complete()
        override def error(cause: Throwable): Unit = delegate.error(cause)
        override def cancel(input: InputHandle): Unit = delegate.cancel(input)
        override def changeCompletionHandling(completion: FlexiMerge.CompletionHandling[Out]): Unit =
          delegate.changeCompletionHandling(wrapCompletionHandling(completion))
      }

    }

    def convertReadCondition(condition: ReadCondition): scaladsl.FlexiMerge.ReadCondition = {
      condition match {
        case r: ReadAny       ⇒ scaladsl.FlexiMerge.ReadAny(immutableIndexedSeq(r.inputs))
        case r: ReadPreferred ⇒ scaladsl.FlexiMerge.ReadPreferred(r.preferred, immutableIndexedSeq(r.secondaries))
        case r: Read          ⇒ scaladsl.FlexiMerge.Read(r.input)
        case r: ReadAll       ⇒ scaladsl.FlexiMerge.ReadAll(new ReadAllInputs(_), immutableIndexedSeq(r.inputs): _*)
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
abstract class FlexiMerge[In, Out](val attributes: OperationAttributes) {
  import FlexiMerge._
  import scaladsl.FlowGraphInternal
  import akka.stream.impl.Ast

  def this(name: String) = this(OperationAttributes.name(name))
  def this() = this(OperationAttributes.none)

  private var inputCount = 0

  def createMergeLogic(): MergeLogic[In, Out]

  // hide the internal vertex things from subclass, and make it possible to create new instance
  private class FlexiMergeVertex(override val attributes: scaladsl.OperationAttributes) extends FlowGraphInternal.InternalVertex {
    override def minimumInputCount = 2
    override def maximumInputCount = inputCount
    override def minimumOutputCount = 1
    override def maximumOutputCount = 1

    override private[akka] val astNode = {
      val factory = new MergeLogicFactory[Any] {
        override def attributes: scaladsl.OperationAttributes = FlexiMergeVertex.this.attributes
        override def createMergeLogic(): scaladsl.FlexiMerge.MergeLogic[Any] =
          new Internal.MergeLogicWrapper(FlexiMerge.this.createMergeLogic().asInstanceOf[MergeLogic[Any, Any]])
      }
      Ast.FlexiMergeNode(factory, flexiMerge and attributes)
    }

    final override def newInstance() = new FlexiMergeVertex(attributes.withoutName)
  }

  /**
   * INTERNAL API
   */
  private[akka] val vertex: FlowGraphInternal.InternalVertex = new FlexiMergeVertex(attributes.asScala)

  /**
   * Output port of the `FlexiMerge` junction. A [[Sink]] can be connected to this output
   * with the [[FlowGraphBuilder]].
   */
  val out: JunctionOutPort[Out] = new JunctionOutPort[Out] {
    override val asScala: scaladsl.JunctionOutPort[Out] = new scaladsl.JunctionOutPort[Out] {
      override def vertex: FlowGraphInternal.Vertex = FlexiMerge.this.vertex
    }
  }

  /**
   * Concrete subclass is supposed to define one or more input ports and
   * they are created by calling this method. Each [[FlexiMerge.InputPort]] can be
   * connected to a [[Source]] with the [[FlowGraphBuilder]].
   * The `InputPort` is also an [[FlexiMerge.InputHandle]], which is passed as parameter
   * to [[FlexiMerge#State]] `onInput` when an input element has been read so that you
   * can know exactly from which input the element was read.
   */
  protected final def createInputPort[T](): InputPort[T, Out] = {
    val port = inputCount
    inputCount += 1
    new InputPort(port, parent = this)
  }

  override def toString = attributes.asScala.nameLifted match {
    case Some(n) ⇒ n
    case None    ⇒ getClass.getSimpleName + "@" + Integer.toHexString(super.hashCode())
  }
}
