/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import java.util.Optional

import akka.event.Logging

import scala.annotation.tailrec
import scala.reflect.{ ClassTag, classTag }
import akka.japi.function
import akka.stream.impl.StreamLayout._
import java.net.URLEncoder

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

  import Attributes._

  /**
   * INTERNAL API
   */
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
  def getAttribute[T <: Attribute](c: Class[T], default: T): T =
    getAttribute(c).orElse(default)

  /**
   * Java API: Get the least specific attribute (added first) of a given `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def getFirstAttribute[T <: Attribute](c: Class[T], default: T): T =
    getFirstAttribute(c).orElse(default)

  /**
   * Java API: Get the most specific attribute (added last) of a given `Class` or subclass thereof.
   */
  def getAttribute[T <: Attribute](c: Class[T]): Optional[T] =
    (attributeList.collectFirst { case attr if c.isInstance(attr) ⇒ c.cast(attr) }).asJava

  /**
   * Java API: Get the least specific attribute (added first) of a given `Class` or subclass thereof.
   */
  def getFirstAttribute[T <: Attribute](c: Class[T]): Optional[T] =
    attributeList.reverseIterator.collectFirst { case attr if c.isInstance(attr) ⇒ c cast attr }.asJava

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

  /**
   * Scala API: Get the most specific attribute (added last) of a given type parameter T `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def get[T <: Attribute: ClassTag](default: T): T =
    get[T] match {
      case Some(a) ⇒ a
      case None    ⇒ default
    }

  /**
   * Scala API: Get the least specific attribute (added first) of a given type parameter T `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  def getFirst[T <: Attribute: ClassTag](default: T): T = {
    getFirst[T] match {
      case Some(a) ⇒ a
      case None    ⇒ default
    }
  }

  /**
   * Scala API: Get the most specific attribute (added last) of a given type parameter T `Class` or subclass thereof.
   */
  def get[T <: Attribute: ClassTag]: Option[T] = {
    val c = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    attributeList.collectFirst { case attr if c.isInstance(attr) ⇒ c.cast(attr) }
  }

  /**
   * Scala API: Get the least specific attribute (added first) of a given type parameter T `Class` or subclass thereof.
   */
  def getFirst[T <: Attribute: ClassTag]: Option[T] = {
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
