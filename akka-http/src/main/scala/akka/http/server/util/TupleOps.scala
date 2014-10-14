/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.util

class TupleOps[T](val tuple: T) extends AnyVal {
  import TupleOps._

  def foldLeft[In](zero: In)(op: Poly2)(implicit fold: FoldLeft[In, T, op.type]): fold.Out = fold(zero, tuple)
}

object TupleOps {
  implicit def enhanceTuple[T: Tuple](tuple: T) = new TupleOps(tuple)

  trait FoldLeft[In, T, Op] {
    type Out
    def apply(zero: In, tuple: T): Out
  }
  object FoldLeft extends TupleFoldInstances {
    import Poly2.Case

    type Aux[In, T, Op, Out0] = FoldLeft[In, T, Op] { type Out = Out0 }

    implicit def t0[In, Op]: Aux[In, Unit, Op, In] =
      new FoldLeft[In, Unit, Op] {
        type Out = In
        def apply(zero: In, tuple: Unit) = zero
      }

    implicit def t1[In, A, Op](implicit f: Case[In, A, Op]): Aux[In, Tuple1[A], Op, f.Out] =
      new FoldLeft[In, Tuple1[A], Op] {
        type Out = f.Out
        def apply(zero: In, tuple: Tuple1[A]) = f(zero, tuple._1)
      }
  }
}

trait Poly2 {
  def at[A, B] = new CaseBuilder[A, B]
  class CaseBuilder[A, B] {
    def apply[R](f: (A, B) ⇒ R) = new Poly2.Case[A, B, Poly2.this.type] {
      type Out = R
      def apply(a: A, b: B) = f(a, b)
    }
  }
}
object Poly2 {
  trait Case[A, B, Op] {
    type Out
    def apply(a: A, b: B): Out
  }
}

