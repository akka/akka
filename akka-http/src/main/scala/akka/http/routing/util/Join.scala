/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing.util

/**
 * `Join` is implicit evidence that tuples `P` and `S` can be joined into tuple `Out`
 */
trait Join[P, S] {
  type Out
  implicit def OutIsTuple: Tuple[Out] = null // we know that Join only produces Tuples
  def apply(prefix: P, suffix: S): Out
}

object Join extends LowLevelJoinImplicits {
  // O(1) shortcut for the Join[Unit, T] case to avoid O(n) runtime in this case
  implicit def join0P[T]: Aux[Unit, T, T] =
    new Join[Unit, T] {
      type Out = T
      def apply(prefix: Unit, suffix: T): Out = suffix
    }
}

trait LowLevelJoinImplicits {
  type Aux[P, S, Out0] = Join[P, S] { type Out = Out0 }

  /*
    O(b.length) joining algorithm:

      def headTail(l: List[T]): (T, List[T])
      def append(l: List[T], t: T): List[T]

      def join(a: List[T], b: List[T]): List[T] =
        if (b.isEmpty) a
        else {
          val (head, tail) = removeFirst(b)
          join(append(a, head), tail)
        }

    The advantage of using this simple type-level algorithm is that we don't need to define 22 * 22 implicits for joining
    but only 22 + 22 (22 for each HeadTail and AppendOne).

    The idea is that `b` will usually be shorter than `a` because `Directive.&` is left-associative so the bigger
    lists will be usually created on the left side.
  */

  implicit def joinP0[P]: Aux[P, Unit, P] =
    new Join[P, Unit] {
      type Out = P
      def apply(prefix: P, suffix: Unit): P = prefix
    }

  implicit def joinN[A, B, H, T, C](implicit r: HeadTail.Aux[B, H, T], a: AppendOne.Aux[A, H, C], inner: Join[C, T]): Aux[A, B, inner.Out] =
    new Join[A, B] {
      type Out = inner.Out
      def apply(prefix: A, suffix: B): Out = {
        val (h, t) = r(suffix)
        inner(a(prefix, h), t)
      }
    }
}

trait AppendOne[P, S] {
  type Out
  def apply(prefix: P, last: S): Out
}
object AppendOne extends AppendOneInstances

trait HeadTail[L] {
  type H
  type T
  def apply(in: L): (H, T)
}
object HeadTail extends HeadTailInstances