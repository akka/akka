/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

/**
 * Reference that can hold either a typed value or an exception.
 *
 * Usage:
 * <pre>
 * scala> ErrRef(1)
 * res0: ErrRef[Int] = ErrRef@a96606
 *  
 * scala> res0()
 * res1: Int = 1
 *
 * scala> res0() = 3
 *
 * scala> res0()
 * res3: Int = 3
 * 
 * scala> res0() = { println("Hello world"); 3}
 * Hello world
 *
 * scala> res0()
 * res5: Int = 3
 *  
 * scala> res0() = error("Lets see what happens here...")
 *
 * scala> res0()
 * java.lang.RuntimeException: Lets see what happens here...
 * 	at ErrRef.apply(RefExcept.scala:11)
 * 	at .<init>(<console>:6)
 * 	at .<clinit>(<console>)
 * 	at Re...
 * </pre>
 */
class ErrRef[S](s: S){
  private[this] var contents: Either[Throwable, S] = Right(s)
  def update(value: => S) = contents = try { Right(value) } catch { case (e : Throwable) => Left(e) }
  def apply() = contents match {
    case Right(s) => s
    case Left(e) => throw e.fillInStackTrace
  }
  override def toString(): String = "ErrRef[" + contents + "]"
}
object ErrRef {
  def apply[S](s: S) = new ErrRef(s)
}
