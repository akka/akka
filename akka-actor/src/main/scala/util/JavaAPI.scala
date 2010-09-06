package se.scalablesolutions.akka.util

/** A Function interface
 * Used to create first-class-functions is Java (sort of)
 * Java API
 */
trait Function[T,R] {
  def apply(param: T): R
}

/** A Procedure is like a Function, but it doesn't produce a return value
 * Java API
 */
trait Procedure[T] {
  def apply(param: T): Unit
}