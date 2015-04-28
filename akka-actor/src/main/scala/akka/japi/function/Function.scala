/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.japi.function

/**
 * A Function interface. Used to create first-class-functions is Java.
 */
trait Function[-T, +R] {
  @throws(classOf[Exception])
  def apply(param: T): R
}

/**
 * A Function interface. Used to create 2-arg first-class-functions is Java.
 */
trait Function2[-T1, -T2, +R] {
  @throws(classOf[Exception])
  def apply(arg1: T1, arg2: T2): R
}

/**
 * A Procedure is like a Function, but it doesn't produce a return value.
 */
trait Procedure[-T] {
  @throws(classOf[Exception])
  def apply(param: T): Unit
}

/**
 * An executable piece of code that takes no parameters and doesn't return any value.
 */
trait Effect {
  @throws(classOf[Exception])
  def apply(): Unit
}

/**
 * Java API: Defines a criteria and determines whether the parameter meets this criteria.
 */
trait Predicate[-T] {
  def test(param: T): Boolean
}

/**
 * A constructor/factory, takes no parameters but creates a new value of type T every call.
 */
@SerialVersionUID(1L)
trait Creator[+T] extends Serializable {
  /**
   * This method must return a different instance upon every call.
   */
  @throws(classOf[Exception])
  def create(): T
}

