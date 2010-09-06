package se.scalablesolutions.akka.util

trait Function[T,R] {
  def apply(param: T): R
}

trait Procedure[T] {
  def apply(param: T): Unit
}