/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream.scaladsl2

import akka.stream.impl2.Ast

object FlexiMerge {

  sealed trait Input[T] extends JunctionInPort[T]

  sealed trait ReadCondition[T]
  final case class Read[T](input: Input[T]) extends ReadCondition[T]
  final case class ReadAnyWithInput[T](inputs: Input[T]*) extends ReadCondition[(T, Input[T])]
  final case class ReadAny[T](inputs: Input[T]*) extends ReadCondition[T]

  sealed case class State[T](condition: ReadCondition[T])(onInput: T ⇒ Unit)
  sealed case class CompletionHandling(onComplete: Input[_] ⇒ Unit, onError: (Input[_], Throwable) ⇒ Unit)

  abstract class MergeLogic[Out] {

    // Will continue to operate until a read becomes unsatisfiable, then it completes
    // Errors are immediately propagated
    val defaultCompletionHandling = CompletionHandling(
      onError = (_, cause) ⇒ error(cause),
      onComplete = _ ⇒ ())

    // Completes as soon as any input completes
    // Errors are immediately propagated
    val eagerClose = CompletionHandling(
      onError = (_, cause) ⇒ error(cause),
      onComplete = _ ⇒ complete())

    // FIXME: Check for empty behavior in the corresponding FanIn
    private var currentBehavior: State[_] = null
    private var currentCompletionHandling: CompletionHandling = defaultCompletionHandling

    protected def become(nextBehavior: State[_]): Unit = currentBehavior = nextBehavior
    protected def become(nextBehavior: State[_], completionHandling: CompletionHandling): Unit = {
      currentBehavior = nextBehavior
      currentCompletionHandling = completionHandling
    }

    protected def emit(elem: Out): Unit = ???
    protected def complete(): Unit = ???
    protected def error(cause: Throwable): Unit = ???
    protected def cancel(input: Input[_]): Unit = ???
  }

  private[akka] class FlexiInput[T, Out](override val port: Int, parent: FlexiMerge[Out]) extends Input[T] {
    override type NextT = Out
    override private[akka] def next = parent.out
    override private[akka] def vertex = parent.vertex
  }

}

abstract class FlexiMerge[Out](val name: Option[String]) {

  def this(name: String) = this(Some(name))
  def this() = this(None)

  import FlexiMerge._
  private var inputCount = 0

  private val vertex = new FlowGraphInternal.InternalVertex {
    override def minimumInputCount = 2
    override def maximumInputCount = inputCount
    override def minimumOutputCount = 1
    override def maximumOutputCount = 1

    override private[akka] val astNode = Ast.FlexiMergeNode(this.asInstanceOf[FlexiMerge[Any]])
    override val name = FlexiMerge.this.name
  }

  val out = new JunctionOutPort[Out] {
    override private[akka] def vertex = FlexiMerge.this.vertex
  }

  def input[T](): Input[T] { type NextT = Out } = {
    val port = inputCount
    inputCount += 1
    new FlexiInput(port, parent = this)
  }

  def createMergeLogic: MergeLogic[Out]
}
