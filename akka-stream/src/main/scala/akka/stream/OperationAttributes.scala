/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import akka.japi.{ function ⇒ japi }
import akka.stream.impl.Stages.StageModule

/**
 * Holds attributes which can be used to alter [[Flow]] or [[FlowGraph]]
 * materialization.
 *
 * Note that more attributes for the [[ActorFlowMaterializer]] are defined in [[ActorOperationAttributes]].
 */
final case class OperationAttributes private (attributes: immutable.Seq[OperationAttributes.Attribute] = Nil) {

  import OperationAttributes._

  /**
   * Java API
   */
  def getAttributes(): java.util.List[Attribute] = {
    import scala.collection.JavaConverters._
    attributes.asJava
  }

  /**
   * Java API: Get all attributes of a given `Class` or
   * subclass thereof.
   */
  def getAttributes[T <: Attribute](c: Class[T]): java.util.List[T] =
    if (attributes.isEmpty) java.util.Collections.emptyList()
    else {
      val result = new java.util.ArrayList[T]
      attributes.foreach { a ⇒
        if (c.isInstance(a))
          result.add(a.asInstanceOf[T])
      }
      result
    }

  /**
   * Get first attribute of a given `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def getAttribute[T <: Attribute](c: Class[T], default: T): T =
    attributes.find(a ⇒ c.isInstance(a)) match {
      case Some(a) ⇒ a.asInstanceOf[T]
      case None    ⇒ default
    }

  /**
   * Adds given attributes to the end of these attributes.
   */
  def and(other: OperationAttributes): OperationAttributes =
    if (attributes.isEmpty) other
    else if (other.attributes.isEmpty) this
    else OperationAttributes(attributes ++ other.attributes)

  /**
   * INTERNAL API
   */
  private[akka] def nameLifted: Option[String] =
    if (attributes.isEmpty)
      None
    else {
      val sb = new java.lang.StringBuilder
      val iter = attributes.iterator
      while (iter.hasNext) {
        iter.next() match {
          case Name(name) ⇒
            if (sb.length == 0) sb.append(name)
            else sb.append("-").append(name)
          case _ ⇒
        }
      }
      if (sb.length == 0) None
      else Some(sb.toString)
    }

  /**
   * INTERNAL API
   */
  private[akka] def nameOrDefault(default: String = "unknown-operation"): String = nameLifted match {
    case Some(name) ⇒ name
    case _          ⇒ default
  }

  /**
   * INTERNAL API
   */
  private[akka] def nameOption: Option[String] =
    attributes.collectFirst { case Name(name) ⇒ name }

  /**
   * INTERNAL API
   */
  private[akka] def transform(node: StageModule): StageModule =
    if ((this eq OperationAttributes.none) || (this eq node.attributes)) node
    else node.withAttributes(attributes = this and node.attributes)

}

/**
 * Note that more attributes for the [[ActorFlowMaterializer]] are defined in [[ActorOperationAttributes]].
 */
object OperationAttributes {

  trait Attribute
  final case class Name(n: String) extends Attribute
  final case class InputBuffer(initial: Int, max: Int) extends Attribute

  /**
   * INTERNAL API
   */
  private[akka] def apply(attribute: Attribute): OperationAttributes =
    apply(List(attribute))

  val none: OperationAttributes = OperationAttributes()

  /**
   * Specifies the name of the operation.
   * If the name is null or empty the name is ignored, i.e. [[#none]] is returned.
   */
  def name(name: String): OperationAttributes =
    if (name == null || name.isEmpty) none
    else OperationAttributes(Name(name))

  /**
   * Specifies the initial and maximum size of the input buffer.
   */
  def inputBuffer(initial: Int, max: Int): OperationAttributes = OperationAttributes(InputBuffer(initial, max))

}

/**
 * Attributes for the [[ActorFlowMaterializer]].
 * Note that more attributes defined in [[OperationAttributes]].
 */
object ActorOperationAttributes {
  import OperationAttributes._
  final case class Dispatcher(dispatcher: String) extends Attribute
  final case class SupervisionStrategy(decider: Supervision.Decider) extends Attribute

  /**
   * Specifies the name of the dispatcher.
   */
  def dispatcher(dispatcher: String): OperationAttributes = OperationAttributes(Dispatcher(dispatcher))

  /**
   * Scala API: Decides how exceptions from user are to be handled.
   */
  def supervisionStrategy(decider: Supervision.Decider): OperationAttributes =
    OperationAttributes(SupervisionStrategy(decider))

  /**
   * Java API: Decides how exceptions from application code are to be handled.
   */
  def withSupervisionStrategy(decider: japi.Function[Throwable, Supervision.Directive]): OperationAttributes =
    ActorOperationAttributes.supervisionStrategy(decider.apply _)
}
