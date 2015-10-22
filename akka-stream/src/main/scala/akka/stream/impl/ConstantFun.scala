/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.japi.function.{ Function ⇒ JFun, Function2 ⇒ JFun2 }
import akka.japi.{ Pair ⇒ JPair }

private[stream] object ConstantFun {
  private[this] val JavaIdentityFunction = new JFun[Any, Any] {
    @throws(classOf[Exception]) override def apply(param: Any): Any = param
  }

  val JavaPairFunction = new JFun2[AnyRef, AnyRef, AnyRef JPair AnyRef] {
    def apply(p1: AnyRef, p2: AnyRef): AnyRef JPair AnyRef = JPair(p1, p2)
  }

  private[this] val ScalaIdentityFunction = (a: Any) ⇒ a

  def javaCreatePairFunction[A, B]: JFun2[A, B, JPair[A, B]] = JavaPairFunction.asInstanceOf[JFun2[A, B, JPair[A, B]]]

  def javaIdentityFunction[T]: JFun[T, T] = JavaIdentityFunction.asInstanceOf[JFun[T, T]]

  def scalaIdentityFunction[T]: T ⇒ T = ScalaIdentityFunction.asInstanceOf[T ⇒ T]
}
