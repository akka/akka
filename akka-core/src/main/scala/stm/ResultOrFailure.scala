/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import stm.Transaction

/**
 * Reference that can hold either a typed value or an exception.
 *
 * Usage:
 * <pre>
 * scala> ResultOrFailure(1)
 * res0: ResultOrFailure[Int] = ResultOrFailure@a96606
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
 *      at ResultOrFailure.apply(RefExcept.scala:11)
 *      at .<init>(<console>:6)
 *      at .<clinit>(<console>)
 *      at Re...
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ResultOrFailure[Payload](payload: Payload, val tx: Option[Transaction]) {
  private[this] var contents: Either[Throwable, Payload] = Right(payload)

  def update(value: => Payload) = {
    contents = try { Right(value) } catch { case (e : Throwable) => Left(e) }
  }

  def apply() = contents match {
    case Right(payload) => payload
    case Left(e) => throw e
  }

  override def toString(): String = "ResultOrFailure[" + contents + "]"
}
object ResultOrFailure {
  def apply[Payload](payload: Payload, tx: Option[Transaction]) = new ResultOrFailure(payload, tx)
  def apply[AnyRef](tx: Option[Transaction]) = new ResultOrFailure(new Object, tx)
}
