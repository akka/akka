/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.MaterializerSettings
import akka.stream.impl.Ast.AstNode

/**
 * Holds attributes which can be used to alter [[Flow]] or [[FlowGraph]]
 * materialization.
 */
case class OperationAttributes private (private val attributes: List[OperationAttributes.Attribute] = Nil) {

  import OperationAttributes._

  /**
   * Adds given attributes to the end of these attributes.
   */
  def and(other: OperationAttributes): OperationAttributes = {
    OperationAttributes(attributes ::: other.attributes)
  }

  private[akka] def nameLifted: Option[String] =
    attributes.collect {
      case Name(name) ⇒ name
    }.reduceOption(_ + "-" + _)

  private[akka] def name: String = nameLifted match {
    case Some(name) ⇒ name
    case _          ⇒ "unknown-operation"
  }

  private[akka] def settings: MaterializerSettings ⇒ MaterializerSettings =
    attributes.collect {
      case InputBuffer(initial, max) ⇒ (s: MaterializerSettings) ⇒ s.withInputBuffer(initial, max)
      case FanOutBuffer(initial, max) ⇒ (s: MaterializerSettings) ⇒ s.withFanOutBuffer(initial, max)
      case Dispatcher(dispatcher) ⇒ (s: MaterializerSettings) ⇒ s.withDispatcher(dispatcher)
    }.reduceOption(_ andThen _).getOrElse(identity)

  private[akka] def transform(node: AstNode): AstNode =
    if ((this eq OperationAttributes.none) || (this eq node.attributes)) node
    else node.withAttributes(attributes = this and node.attributes)

  /**
   * Filtering out name attributes is needed for Vertex.newInstance().
   * However there is an ongoing discussion for removing this feature,
   * after which this will not be needed anymore.
   *
   * https://github.com/akka/akka/issues/16392
   */
  private[akka] def withoutName = this.copy(
    attributes = attributes.filterNot {
      case attr: Name ⇒ true
    })
}

object OperationAttributes {

  private[OperationAttributes] trait Attribute
  private[OperationAttributes] case class Name(n: String) extends Attribute
  private[OperationAttributes] case class InputBuffer(initial: Int, max: Int) extends Attribute
  private[OperationAttributes] case class FanOutBuffer(initial: Int, max: Int) extends Attribute
  private[OperationAttributes] case class Dispatcher(dispatcher: String) extends Attribute

  private[OperationAttributes] def apply(attribute: Attribute): OperationAttributes =
    apply(List(attribute))

  private[akka] val none: OperationAttributes = OperationAttributes()

  /**
   * Specifies the name of the operation.
   */
  def name(name: String): OperationAttributes = OperationAttributes(Name(name))

  /**
   * Specifies the initial and maximum size of the input buffer.
   */
  def inputBuffer(initial: Int, max: Int): OperationAttributes = OperationAttributes(InputBuffer(initial, max))

  /**
   * Specifies the initial and maximum size of the fan out buffer.
   */
  def fanOutBuffer(initial: Int, max: Int): OperationAttributes = OperationAttributes(FanOutBuffer(initial, max))

  /**
   * Specifies the name of the dispatcher.
   */
  def dispatcher(dispatcher: String): OperationAttributes = OperationAttributes(Dispatcher(dispatcher))
}
