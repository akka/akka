/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.japi.function.Function

package object javadsl {

  val JavaIdentityFunction = new Function[Any, Any] {
    @throws(classOf[Exception])
    override def apply(param: Any): Any = param
  }

  def javaIdentityFunction[T] = JavaIdentityFunction.asInstanceOf[Function[T, T]]

  def combinerToScala[M1, M2, M](f: akka.japi.function.Function2[M1, M2, M]): (M1, M2) ⇒ M =
    f match {
      case s: Function2[_, _, _] ⇒ s.asInstanceOf[(M1, M2) ⇒ M]
      case other                 ⇒ other.apply _
    }

  def combinerToJava[M1, M2, M](f: (M1, M2) ⇒ M): akka.japi.function.Function2[M1, M2, M] =
    new akka.japi.function.Function2[M1, M2, M] { def apply(m1: M1, m2: M2): M = f.apply(m1, m2) }

}
