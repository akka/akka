/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.event.Logging

import scala.annotation.tailrec
import scala.collection.immutable
import akka.stream.impl.Stages.StageModule
import akka.japi.function

/**
 * Holds attributes which can be used to alter [[akka.stream.scaladsl.Flow]] / [[akka.stream.javadsl.Flow]]
 * or [[akka.stream.scaladsl.FlowGraph]] / [[akka.stream.javadsl.FlowGraph]] materialization.
 *
 * Note that more attributes for the [[ActorMaterializer]] are defined in [[ActorAttributes]].
 */
final case class Attributes private (attributeList: immutable.Seq[Attributes.Attribute] = Nil) {

  import Attributes._

  /**
   * Java API
   */
  def getAttributeList(): java.util.List[Attribute] = {
    import scala.collection.JavaConverters._
    attributeList.asJava
  }

  /**
   * Java API: Get all attributes of a given `Class` or
   * subclass thereof.
   */
  def getAttributeList[T <: Attribute](c: Class[T]): java.util.List[T] =
    if (attributeList.isEmpty) java.util.Collections.emptyList()
    else {
      val result = new java.util.ArrayList[T]
      attributeList.foreach { a ⇒
        if (c.isInstance(a))
          result.add(c.cast(a))
      }
      result
    }

  /**
   * Get first attribute of a given `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def getAttribute[T <: Attribute](c: Class[T], default: T): T =
    attributeList.find(c.isInstance) match {
      case Some(a) ⇒ c.cast(a)
      case None    ⇒ default
    }

  /**
   * Adds given attributes to the end of these attributes.
   */
  def and(other: Attributes): Attributes =
    if (attributeList.isEmpty) other
    else if (other.attributeList.isEmpty) this
    else Attributes(attributeList ++ other.attributeList)

  /**
   * INTERNAL API
   */
  private[akka] def nameLifted: Option[String] = Option(nameOrDefault(null))

  /**
   * INTERNAL API
   */
  private[akka] def nameOrDefault(default: String = "unknown-operation"): String = {
    @tailrec def concatNames(i: Iterator[Attribute], first: String, buf: StringBuilder): String =
      if (i.hasNext)
        i.next() match {
          case Name(n) ⇒
            if (buf ne null) concatNames(i, null, buf.append('-').append(n))
            else if (first ne null) {
              val b = new StringBuilder(
                (first.length() + n.length()) match {
                  case x if x < 0                ⇒ throw new IllegalStateException("Names too long to concatenate")
                  case y if y > Int.MaxValue / 2 ⇒ Int.MaxValue
                  case z                         ⇒ Math.max(Integer.highestOneBit(z) * 2, 32)
                })
              concatNames(i, null, b.append(first).append('-').append(n))
            } else concatNames(i, n, null)
          case _ ⇒ concatNames(i, first, buf)
        }
      else if (buf eq null) first
      else buf.toString

    concatNames(attributeList.iterator, null, null) match {
      case null ⇒ default
      case some ⇒ some
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def logLevels: Option[LogLevels] =
    attributeList.collectFirst { case l: LogLevels ⇒ l }

  private[akka] def transform(node: StageModule): StageModule =
    if ((this eq Attributes.none) || (this eq node.attributes)) node
    else node.withAttributes(attributes = this and node.attributes)

}

/**
 * Note that more attributes for the [[ActorMaterializer]] are defined in [[ActorAttributes]].
 */
object Attributes {

  trait Attribute
  final case class Name(n: String) extends Attribute
  final case class InputBuffer(initial: Int, max: Int) extends Attribute
  final case class LogLevels(onElement: Logging.LogLevel, onFinish: Logging.LogLevel, onFailure: Logging.LogLevel) extends Attribute
  object LogLevels {
    /** Use to disable logging on certain operations when configuring [[Attributes.LogLevels]] */
    final val Off: Logging.LogLevel = Logging.levelFor("off").get
  }

  /**
   * INTERNAL API
   */
  private[akka] def apply(attribute: Attribute): Attributes =
    apply(List(attribute))

  val none: Attributes = Attributes()

  /**
   * Specifies the name of the operation.
   * If the name is null or empty the name is ignored, i.e. [[#none]] is returned.
   */
  def name(name: String): Attributes =
    if (name == null || name.isEmpty) none
    else Attributes(Name(name))

  /**
   * Specifies the initial and maximum size of the input buffer.
   */
  def inputBuffer(initial: Int, max: Int): Attributes = Attributes(InputBuffer(initial, max))

  /**
   * Java API
   *
   * Configures `log()` stage log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[LogLevels.Off]].
   *
   * Passing in null as any of the arguments sets the level to its default value, which is:
   * `Debug` for `onElement` and `onFinish`, and `Error` for `onFailure`.
   */
  def createLogLevels(onElement: Logging.LogLevel, onFinish: Logging.LogLevel, onFailure: Logging.LogLevel) =
    logLevels(
      onElement = Option(onElement).getOrElse(Logging.DebugLevel),
      onFinish = Option(onFinish).getOrElse(Logging.DebugLevel),
      onFailure = Option(onFailure).getOrElse(Logging.ErrorLevel))

  /**
   * Configures `log()` stage log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[LogLevels.Off]].
   *
   * See [[Attributes.createLogLevels]] for Java API
   */
  def logLevels(onElement: Logging.LogLevel = Logging.DebugLevel, onFinish: Logging.LogLevel = Logging.DebugLevel, onFailure: Logging.LogLevel = Logging.ErrorLevel) =
    Attributes(LogLevels(onElement, onFinish, onFailure))

}

/**
 * Attributes for the [[ActorMaterializer]].
 * Note that more attributes defined in [[Attributes]].
 */
object ActorAttributes {
  import Attributes._
  final case class Dispatcher(dispatcher: String) extends Attribute
  final case class SupervisionStrategy(decider: Supervision.Decider) extends Attribute

  /**
   * Specifies the name of the dispatcher.
   */
  def dispatcher(dispatcher: String): Attributes = Attributes(Dispatcher(dispatcher))

  /**
   * Scala API: Decides how exceptions from user are to be handled.
   */
  def supervisionStrategy(decider: Supervision.Decider): Attributes =
    Attributes(SupervisionStrategy(decider))

  /**
   * Java API: Decides how exceptions from application code are to be handled.
   */
  def withSupervisionStrategy(decider: function.Function[Throwable, Supervision.Directive]): Attributes =
    ActorAttributes.supervisionStrategy(decider.apply _)

  /**
   * Java API
   *
   * Configures `log()` stage log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[LogLevels.Off]].
   *
   * Passing in null as any of the arguments sets the level to its default value, which is:
   * `Debug` for `onElement` and `onFinish`, and `Error` for `onFailure`.
   */
  def createLogLevels(onElement: Logging.LogLevel, onFinish: Logging.LogLevel, onFailure: Logging.LogLevel) =
    logLevels(
      onElement = Option(onElement).getOrElse(Logging.DebugLevel),
      onFinish = Option(onFinish).getOrElse(Logging.DebugLevel),
      onFailure = Option(onFailure).getOrElse(Logging.ErrorLevel))

  /**
   * Configures `log()` stage log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[LogLevels.Off]].
   *
   * See [[Attributes.createLogLevels]] for Java API
   */
  def logLevels(onElement: Logging.LogLevel = Logging.DebugLevel, onFinish: Logging.LogLevel = Logging.DebugLevel, onFailure: Logging.LogLevel = Logging.ErrorLevel) =
    Attributes(LogLevels(onElement, onFinish, onFailure))

}
