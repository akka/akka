/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

package object javadsl {
  def combinerToScala[M1, M2, M](f: akka.japi.function.Function2[M1, M2, M]): (M1, M2) ⇒ M =
    f match {
      case s: Function2[_, _, _] ⇒ s.asInstanceOf[(M1, M2) ⇒ M]
      case other                 ⇒ other.apply _
    }
}
