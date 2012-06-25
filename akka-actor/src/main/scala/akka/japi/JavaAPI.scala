/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.japi

import scala.Some
import scala.util.control.NoStackTrace

/**
 * A Function interface. Used to create first-class-functions is Java.
 */
trait Function[T, R] {
  def apply(param: T): R
}

/**
 * A Function interface. Used to create 2-arg first-class-functions is Java.
 */
trait Function2[T1, T2, R] {
  def apply(arg1: T1, arg2: T2): R
}

/**
 * A Procedure is like a Function, but it doesn't produce a return value.
 */
trait Procedure[T] {
  def apply(param: T): Unit
}

/**
 * An executable piece of code that takes no parameters and doesn't return any value.
 */
trait Effect {
  def apply(): Unit
}

/**
 * A constructor/factory, takes no parameters but creates a new value of type T every call.
 */
trait Creator[T] {
  /**
   * This method must return a different instance upon every call.
   */
  def create(): T
}

object PurePartialFunction {
  case object NoMatch extends RuntimeException with NoStackTrace
}

/**
 * Helper for implementing a *pure* partial function: it will possibly be
 * invoked multiple times for a single “application”, because its only abstract
 * method is used for both isDefinedAt() and apply(); the former is mapped to
 * `isCheck == true` and the latter to `isCheck == false` for those cases where
 * this is important to know.
 *
 * {{{
 * new PurePartialFunction<Object, String>() {
 *   public String apply(Object in, boolean isCheck) {
 *     if (in instanceof TheThing) {
 *       if (isCheck) return null; // to spare the expensive or side-effecting code
 *       return doSomethingWithTheThing((TheThing) in);
 *     } else {
 *       throw noMatch();
 *     }
 *   }
 * }
 * }}}
 *
 * The typical use of partial functions from Akka looks like the following:
 *
 * {{{
 * if (pf.isDefinedAt(x)) pf.apply(x)
 * }}}
 *
 * i.e. it will first call `PurePartialFunction.apply(x, true)` and if that
 * does not throw `noMatch()` it will continue with calling
 * `PurePartialFunction.apply(x, false)`.
 */
abstract class PurePartialFunction[A, B] extends scala.runtime.AbstractFunction1[A, B] with PartialFunction[A, B] {
  import PurePartialFunction._

  def apply(x: A, isCheck: Boolean): B

  final def isDefinedAt(x: A): Boolean = try { apply(x, true); true } catch { case NoMatch ⇒ false }
  final def apply(x: A): B = try apply(x, false) catch { case NoMatch ⇒ throw new MatchError }
  final def noMatch(): RuntimeException = NoMatch
}

/**
 * This class represents optional values. Instances of <code>Option</code>
 * are either instances of case class <code>Some</code> or it is case
 * object <code>None</code>.
 * <p>
 * Java API
 */
sealed abstract class Option[A] extends java.lang.Iterable[A] {
  import scala.collection.JavaConversions._

  def get: A
  def isEmpty: Boolean
  def isDefined: Boolean = !isEmpty
  def asScala: scala.Option[A]
  def iterator: java.util.Iterator[A] = if (isEmpty) Iterator.empty else Iterator.single(get)
}

object Option {
  /**
   * <code>Option</code> factory that creates <code>Some</code>
   */
  def some[A](v: A): Option[A] = Some(v)

  /**
   * <code>Option</code> factory that creates <code>None</code>
   */
  def none[A] = None.asInstanceOf[Option[A]]

  /**
   * <code>Option</code> factory that creates <code>None</code> if
   * <code>v</code> is <code>null</code>, <code>Some(v)</code> otherwise.
   */
  def option[A](v: A): Option[A] = if (v == null) none else some(v)

  /**
   * Converts a Scala Option to a Java Option
   */
  def fromScalaOption[T](scalaOption: scala.Option[T]): Option[T] = scalaOption match {
    case scala.Some(r) ⇒ some(r)
    case scala.None    ⇒ none
  }

  /**
   * Class <code>Some[A]</code> represents existing values of type
   * <code>A</code>.
   */
  final case class Some[A](v: A) extends Option[A] {
    def get: A = v
    def isEmpty: Boolean = false
    def asScala: scala.Some[A] = scala.Some(v)
  }

  /**
   * This case object represents non-existent values.
   */
  private case object None extends Option[Nothing] {
    def get: Nothing = throw new NoSuchElementException("None.get")
    def isEmpty: Boolean = true
    def asScala: scala.None.type = scala.None
  }

  implicit def java2ScalaOption[A](o: Option[A]): scala.Option[A] = o.asScala
  implicit def scala2JavaOption[A](o: scala.Option[A]): Option[A] = if (o.isDefined) some(o.get) else none
}

/**
 * This class hold common utilities for Java
 */
object Util {
  /**
   * Given a Class returns a Scala Manifest of that Class
   */
  def manifest[T](clazz: Class[T]): Manifest[T] = Manifest.classType(clazz)

  def arrayToSeq[T](arr: Array[T]): Seq[T] = arr.toSeq
}
