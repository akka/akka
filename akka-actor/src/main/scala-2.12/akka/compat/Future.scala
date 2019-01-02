/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.compat

import akka.annotation.InternalApi
import scala.concurrent.{ ExecutionContext, Future ⇒ SFuture }
import scala.collection.immutable

/**
 * INTERNAL API
 *
 * Compatibility wrapper for `scala.concurrent.Future` to be able to compile the same code
 * against Scala 2.11, 2.12, 2.13
 *
 * Remove these classes as soon as support for Scala 2.11 is dropped!
 */
@InternalApi private[akka] object Future {
  def fold[T, R](futures: TraversableOnce[SFuture[T]])(zero: R)(op: (R, T) ⇒ R)(implicit executor: ExecutionContext): SFuture[R] =
    SFuture.fold[T, R](futures)(zero)(op)(executor)

  def fold[T, R](futures: immutable.Iterable[SFuture[T]])(zero: R)(op: (R, T) ⇒ R)(implicit executor: ExecutionContext): SFuture[R] =
    SFuture.foldLeft[T, R](futures)(zero)(op)(executor)

  def reduce[T, R >: T](futures: TraversableOnce[SFuture[T]])(op: (R, T) ⇒ R)(implicit executor: ExecutionContext): SFuture[R] =
    SFuture.reduce[T, R](futures)(op)(executor)

  def reduce[T, R >: T](futures: immutable.Iterable[SFuture[T]])(op: (R, T) ⇒ R)(implicit executor: ExecutionContext): SFuture[R] =
    SFuture.reduceLeft[T, R](futures)(op)(executor)

  def find[T](futures: TraversableOnce[SFuture[T]])(p: T ⇒ Boolean)(implicit executor: ExecutionContext): SFuture[Option[T]] =
    SFuture.find[T](futures)(p)(executor)

  def find[T](futures: immutable.Iterable[SFuture[T]])(p: T ⇒ Boolean)(implicit executor: ExecutionContext): SFuture[Option[T]] =
    SFuture.find[T](futures)(p)(executor)
}

