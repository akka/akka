/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Stages.StageModule
import akka.stream.Supervision

/**
 * Holds attributes which can be used to alter [[Flow]] or [[FlowGraph]]
 * materialization.
 */
final case class OperationAttributes private (attributes: List[OperationAttributes.Attribute] = Nil) {

  import OperationAttributes._

  /**
   * Adds given attributes to the end of these attributes.
   */
  def and(other: OperationAttributes): OperationAttributes =
    if (attributes.isEmpty) other
    else if (other.attributes.isEmpty) this
    else OperationAttributes(attributes ::: other.attributes)

  private[akka] def nameLifted: Option[String] =
    attributes.collect {
      case Name(name) ⇒ name
    }.reduceOption(_ + "-" + _) // FIXME don't do a double-traversal, use a fold instead

  private[akka] def name: String = nameLifted match {
    case Some(name) ⇒ name
    case _          ⇒ "unknown-operation"
  }

  private[akka] def transform(node: StageModule): StageModule =
    if ((this eq OperationAttributes.none) || (this eq node.attributes)) node
    else node.withAttributes(attributes = this and node.attributes)

  /**
   * Filtering out name attributes is needed for Vertex.newInstance().
   * However there is an ongoing discussion for removing this feature,
   * after which this will not be needed anymore.
   *
   * https://github.com/akka/akka/issues/16392
   */
  private[akka] def withoutName = this.copy( // FIXME should return OperationAttributes.none if empty
    attributes = attributes.filterNot { // FIXME should return the same instance if didn't have any Name
      case attr: Name ⇒ true
    })
}

object OperationAttributes {

  sealed trait Attribute
  final case class Name(n: String) extends Attribute
  final case class InputBuffer(initial: Int, max: Int) extends Attribute
  final case class Dispatcher(dispatcher: String) extends Attribute
  final case class SupervisionStrategy(decider: Supervision.Decider) extends Attribute

  private[OperationAttributes] def apply(attribute: Attribute): OperationAttributes =
    apply(List(attribute))

  val none: OperationAttributes = OperationAttributes()

  /**
   * Specifies the name of the operation.
   */
  def name(name: String): OperationAttributes = OperationAttributes(Name(name))

  /**
   * Specifies the initial and maximum size of the input buffer.
   */
  def inputBuffer(initial: Int, max: Int): OperationAttributes = OperationAttributes(InputBuffer(initial, max))

  /**
   * Specifies the name of the dispatcher.
   */
  def dispatcher(dispatcher: String): OperationAttributes = OperationAttributes(Dispatcher(dispatcher))

  /**
   * Decides how exceptions from user are to be handled.
   */
  def supervisionStrategy(decider: Supervision.Decider): OperationAttributes =
    OperationAttributes(SupervisionStrategy(decider))
}
