/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import java.util.Optional

import akka.event.Logging

import scala.annotation.tailrec
import scala.reflect.{ ClassTag, classTag }
import akka.japi.function
import java.net.URLEncoder

import akka.annotation.InternalApi
import akka.stream.Attributes.Important
import akka.stream.impl.TraversalBuilder

import scala.compat.java8.OptionConverters._
import akka.util.ByteString

/**
 * Holds attributes which can be used to alter [[akka.stream.scaladsl.Flow]] / [[akka.stream.javadsl.Flow]]
 * or [[akka.stream.scaladsl.GraphDSL]] / [[akka.stream.javadsl.GraphDSL]] materialization.
 *
 * Note that more attributes for the [[ActorMaterializer]] are defined in [[ActorAttributes]].
 *
 * The ``attributeList`` is ordered with the most specific attribute first, least specific last.
 * Note that the order was the opposite in Akka 2.4.x.
 */
final case class Attributes(attributeList: List[Attributes.Attribute] = Nil) {
  /**
   * Mark the most recently added attribute as important.
   *
   * Meant for the case where a factory method returns an `Attributes` instance instead
   * of a single `Attribute`. For example `ActorAttributes.dispatcher.important`.
   *
   * @see [[Important]]
   */
  def important: Attributes = attributeList match {
    case Nil            ⇒ this
    case single :: rest ⇒ Attributes(Important(single) :: rest)
  }

  import Attributes._

  /**
   * INTERNAL API
   */
  @InternalApi
  private[stream] def isAsync: Boolean = {
    attributeList.nonEmpty && attributeList.exists {
      case AsyncBoundary                 ⇒ true
      case ActorAttributes.Dispatcher(_) ⇒ true
      case _                             ⇒ false
    }
  }

  /**
   * Java API
   *
   * The list is ordered with the most specific attribute first, least specific last.
   * Note that the order was the opposite in Akka 2.4.x.
   */
  def getAttributeList(): java.util.List[Attribute] = {
    import scala.collection.JavaConverters._
    attributeList.asJava
  }

  /**
   * Java API: Get all attributes of a given `Class` or
   * subclass thereof.
   *
   * The list is ordered with the most specific attribute first, least specific last.
   * Note that the order was the opposite in Akka 2.4.x.
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
   * Java API: Get the most specific attribute (added last) of a given `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def getMostSpecificOrElse[T <: Attribute](c: Class[T], default: T): T =
    getMostSpecific(c).orElse(default)

  @deprecated("Replaced with getMostSpecificOrElse", "2.5.7")
  def getAttribute[T <: Attribute](c: Class[T], default: T): T = getMostSpecificOrElse(c, default)

  /**
   * Java API: Get the least specific attribute (added first) of a given `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def getLeastSpecificOrElse[T <: Attribute](c: Class[T], default: T): T =
    getLeastSpecific(c).orElse(default)

  @deprecated("Replaced with getLeastSpecificOrElse", "2.5.7")
  def getFirstAttribute[T <: Attribute](c: Class[T], default: T): T = getLeastSpecificOrElse(c, default)

  /**
   * Java API: Get the most specific attribute (added last) of a given `Class` or subclass thereof.
   *
   * @see [[#getMostSpecificOrElse()]]
   */
  def getMostSpecific[T <: Attribute](c: Class[T]): Optional[T] =
    (attributeList.collectFirst { case attr if c.isInstance(attr) ⇒ c.cast(attr) }).asJava

  @deprecated("Replaced with getMostSpecific", "2.5.7")
  def getAttribute[T <: Attribute](c: Class[T]): Optional[T] = getMostSpecific(c)

  /**
   * Java API: Get the least specific attribute (added first) of a given `Class` or subclass thereof.
   *
   * @see [[#getLeastSpecificOrElse()]]
   */
  def getLeastSpecific[T <: Attribute](c: Class[T]): Optional[T] =
    attributeList.reverseIterator.collectFirst { case attr if c.isInstance(attr) ⇒ c cast attr }.asJava

  @deprecated("Replaced with getLeastSpecific", "2.5.7")
  def getFirstAttribute[T <: Attribute](c: Class[T]): Optional[T] = getLeastSpecific(c)

  /**
   * Scala API: get all attributes of a given type (or subtypes thereof).
   *
   * The list is ordered with the most specific attribute first, least specific last.
   * Note that the order was the opposite in Akka 2.4.x.
   */
  def filtered[T <: Attribute: ClassTag]: List[T] = {
    val c = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
    attributeList.collect { case attr if c.isAssignableFrom(attr.getClass) ⇒ c.cast(attr) }
  }

  @deprecated("Replaced with mostSpecificOrElse", "2.5.7")
  def get[T <: Attribute: ClassTag](default: T): T = mostSpecificOrElse(default)

  /**
   * Scala API: Get the most specific attribute (added last) of a given type parameter T `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def mostSpecificOrElse[T <: Attribute: ClassTag](default: T): T =
    mostSpecific[T] match {
      case Some(a) ⇒ a
      case None    ⇒ default
    }

  @deprecated("Replaced with leastSpecificOrElse", "2.5.7")
  def getFirst[T <: Attribute: ClassTag](default: T): T = leastSpecificOrElse(default)

  /**
   * Scala API: Get the least specific attribute (added first) of a given type parameter T `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def leastSpecificOrElse[T <: Attribute: ClassTag](default: T): T = {
    leastSpecific[T] match {
      case Some(a) ⇒ a
      case None    ⇒ default
    }
  }

  @deprecated("Replaced with leastSpecificOrElse", "2.5.7")
  def get[T <: Attribute: ClassTag]: Option[T] = mostSpecific[T]

  /**
   * Scala API: Get the most specific attribute (added last) of a given type parameter T `Class` or subclass thereof.
   *
   * @see [[#mostSpecificOrElse()]]
   */
  def mostSpecific[T <: Attribute: ClassTag]: Option[T] = {
    val c = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    attributeList.collectFirst { case Important(attr) if c.isInstance(attr) ⇒ c.cast(attr) } match {
      // prefer the important ones
      case found: Some[T] ⇒
        found
      // fallback to "normal"
      case None ⇒
        attributeList.collectFirst { case attr if c.isInstance(attr) ⇒ c.cast(attr) }
    }
  }

  @deprecated("Replaced with leastSpecific", "2.5.7")
  def getFirst[T <: Attribute: ClassTag]: Option[T] = leastSpecific[T]

  /**
   * Scala API: Get the least specific attribute (added first) of a given type parameter T `Class` or subclass thereof.
   *
   * @see [[#leastSpecificOrElse()]]
   */
  def leastSpecific[T <: Attribute: ClassTag]: Option[T] = {
    val c = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    attributeList.reverseIterator.collectFirst { case attr if c.isInstance(attr) ⇒ c.cast(attr) }
  }

  /**
   * Test whether the given attribute is contained within this attributes list.
   */
  def contains(attr: Attribute): Boolean = attributeList.contains(attr)

  /**
   * Adds given attributes. Added attributes are considered more specific than
   * already existing attributes of the same type.
   */
  def and(other: Attributes): Attributes = {
    if (attributeList.isEmpty) other
    else if (other.attributeList.isEmpty) this
    else if (other.attributeList.tail.isEmpty) Attributes(other.attributeList.head :: attributeList)
    else Attributes(other.attributeList ::: attributeList)
  }

  /**
   * Adds given attribute. Added attribute is considered more specific than
   * already existing attributes of the same type.
   */
  def and(other: Attribute): Attributes =
    Attributes(other :: attributeList)

  /**
   * Extracts Name attributes and concatenates them.
   */
  def nameLifted: Option[String] = {
    @tailrec def concatNames(i: Iterator[Attribute], first: String, buf: java.lang.StringBuilder): String =
      if (i.hasNext)
        i.next() match {
          case Name(n) ⇒
            if (buf ne null) concatNames(i, null, buf.append('-').append(n))
            else if (first ne null) {
              val b = new java.lang.StringBuilder((first.length + n.length) * 2)
              concatNames(i, null, b.append(first).append('-').append(n))
            } else concatNames(i, n, null)
          case _ ⇒ concatNames(i, first, buf)
        }
      else if (buf eq null) first
      else buf.toString

    Option(concatNames(attributeList.reverseIterator, null, null))
  }

  /**
   * INTERNAL API
   */
  def nameOrDefault(default: String = "unnamed"): String = {
    @tailrec def find(attrs: List[Attribute]): String = attrs match {
      case Attributes.Name(name) :: _ ⇒ name
      case _ :: tail                  ⇒ find(tail)
      case Nil                        ⇒ default
    }
    find(attributeList)
  }

}

/**
 * Note that more attributes for the [[ActorMaterializer]] are defined in [[ActorAttributes]].
 */
object Attributes {

  trait Attribute

  /**
   * Wrap an attribute with this to mark it as as important.
   *
   * When attributes are resolved in the stages, most specific important
   * attributes are searched first and only if no important attribute of a type is found the
   * "normal" most specific attributes are looked for
   *
   * Should be used sparingly, and especially avoided by libraries as marking an attribute
   * important inside of a composed graph returned from a user API effectively seals it and
   * makes it impossible to override the attribute for the end user.
   *
   * @param attribute The attribute value marked as important
   */
  final case class Important(attribute: Attribute) extends Attribute

  final case class Name(n: String) extends Attribute
  final case class InputBuffer(initial: Int, max: Int) extends Attribute
  final case class LogLevels(onElement: Logging.LogLevel, onFinish: Logging.LogLevel, onFailure: Logging.LogLevel) extends Attribute
  final case object AsyncBoundary extends Attribute

  object LogLevels {
    /** Use to disable logging on certain operations when configuring [[Attributes.LogLevels]] */
    final val Off: Logging.LogLevel = Logging.levelFor("off").get
  }

  /**
   * INTERNAL API
   */
  def apply(attribute: Attribute): Attributes =
    apply(attribute :: Nil)

  val none: Attributes = Attributes()

  val asyncBoundary: Attributes = Attributes(AsyncBoundary)

  /**
   * Specifies the name of the operation.
   * If the name is null or empty the name is ignored, i.e. [[#none]] is returned.
   *
   * When using this method the name is encoded with URLEncoder with UTF-8 because
   * the name is sometimes used as part of actor name. If that is not desired
   * the name can be added in it's raw format using `.addAttributes(Attributes(Name(name)))`.
   */
  def name(name: String): Attributes =
    if (name == null || name.isEmpty) none
    else Attributes(Name(URLEncoder.encode(name, ByteString.UTF_8)))

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

  /**
   * Compute a name by concatenating all Name attributes that the given module
   * has, returning the given default value if none are found.
   */
  def extractName(builder: TraversalBuilder, default: String): String = {
    builder.attributes.nameOrDefault(default)
  }
}

/**
 * Attributes for the [[ActorMaterializer]].
 * Note that more attributes defined in [[Attributes]].
 */
object ActorAttributes {
  import Attributes._
  final case class Dispatcher(dispatcher: String) extends Attribute
  final case class SupervisionStrategy(decider: Supervision.Decider) extends Attribute

  val IODispatcher: Dispatcher = ActorAttributes.Dispatcher("akka.stream.default-blocking-io-dispatcher")

  /**
   * Specifies the name of the dispatcher. This also adds an async boundary.
   */
  def dispatcher(dispatcher: String): Attributes = Attributes(Dispatcher(dispatcher))

  /**
   * Scala API: Decides how exceptions from user are to be handled.
   *
   * Stages supporting supervision strategies explicitly document that they do so. If a stage does not document
   * support for these, it should be assumed it does not support supervision.
   */
  def supervisionStrategy(decider: Supervision.Decider): Attributes =
    Attributes(SupervisionStrategy(decider))

  /**
   * Java API: Decides how exceptions from application code are to be handled.
   *
   * Stages supporting supervision strategies explicitly document that they do so. If a stage does not document
   * support for these, it should be assumed it does not support supervision.
   */
  def withSupervisionStrategy(decider: function.Function[Throwable, Supervision.Directive]): Attributes =
    ActorAttributes.supervisionStrategy(decider.apply)

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
