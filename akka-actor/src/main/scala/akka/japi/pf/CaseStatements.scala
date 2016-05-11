/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf

import FI.{ UnitApply, Apply, Predicate }

private[pf] object CaseStatement {
  def empty[F, T](): PartialFunction[F, T] = PartialFunction.empty
}

private[pf] class CaseStatement[-A, +B](predicate: FI.TypedPredicate[_ >: A], apply: FI.Apply[_ >: A, _ <: B])
  extends PartialFunction[A, B] {

  override def isDefinedAt(a: A): Boolean = predicate.defined(a)

  override def apply(a: A): B = apply.apply(a)
}
