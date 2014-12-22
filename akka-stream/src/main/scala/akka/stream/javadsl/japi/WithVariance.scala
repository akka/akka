/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl.japi

// TODO Same SAM-classes as in akka.japi, but with variance annotations
// TODO Remove these in favour of using akka.japi with added variance

/**
 * A Function interface. Used to create first-class-functions is Java.
 */
@SerialVersionUID(1L) // FIXME: add variance to akka.japi and remove this akka.stream.japi!
trait Function[-T, +R] {
  @throws(classOf[Exception])
  def apply(param: T): R
}

/**
 * A Function interface. Used to create 2-arg first-class-functions is Java.
 */
@SerialVersionUID(1L) // FIXME: add variance to akka.japi and remove this akka.stream.japi!
trait Function2[-T1, -T2, +R] {
  @throws(classOf[Exception])
  def apply(arg1: T1, arg2: T2): R
}

/**
 * A constructor/factory, takes no parameters but creates a new value of type T every call.
 */
@SerialVersionUID(1L) // FIXME: add variance to akka.japi and remove this akka.stream.japi!
trait Creator[+T] extends Serializable {
  /**
   * This method must return a different instance upon every call.
   */
  @throws(classOf[Exception])
  def create(): T
}

/**
 * A Procedure is like a Function, but it doesn't produce a return value.
 */
@SerialVersionUID(1L) // FIXME: add variance to akka.japi and remove this akka.stream.japi!
trait Procedure[-T] {
  @throws(classOf[Exception])
  def apply(param: T): Unit
}

/**
 * Java API: Defines a criteria and determines whether the parameter meets this criteria.
 */
@SerialVersionUID(1L) // FIXME: add variance to akka.japi and remove this akka.stream.japi!
trait Predicate[-T] {
  def test(param: T): Boolean
}

