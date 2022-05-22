/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.dispatch.ExecutionContexts

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Convenience functions for often-encountered purposes like keeping only the
 * left (first) or only the right (second) of two input values.
 */
object Keep {
  private val _left = (l: Any, _: Any) => l
  private val _right = (_: Any, r: Any) => r
  private val _both = (l: Any, r: Any) => (l, r)
  private val _none = (_: Any, _: Any) => NotUsed

  def left[L, R]: (L, R) => L = _left.asInstanceOf[(L, R) => L]
  def right[L, R]: (L, R) => R = _right.asInstanceOf[(L, R) => R]
  def both[L, R]: (L, R) => (L, R) = _both.asInstanceOf[(L, R) => (L, R)]
  def none[L, R]: (L, R) => NotUsed = _none.asInstanceOf[(L, R) => NotUsed]
  def zip[T, That <: Iterable[T]]: (That, That) => Iterable[(T, T)] =
    (l: That, r: That) => l.zip(r)
  def zipFuture[T, That <: Iterable[T]]: (Future[That], Future[That]) => Future[Iterable[(T, T)]] =
    (l: Future[That], r: Future[That]) => {
      implicit val ec: ExecutionContext = ExecutionContexts.parasitic
      for {
        l_ <- l
        r_ <- r
      } yield l_.zip(r_)
    }
}
