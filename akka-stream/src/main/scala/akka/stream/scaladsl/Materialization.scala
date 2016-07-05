/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Future

import akka.NotUsed

/**
 * Convenience functions for often-encountered purposes like keeping only the
 * left (first) or only the right (second) of two input values.
 */
object Keep {
  private val _left = (l: Any, r: Any) ⇒ l
  private val _right = (l: Any, r: Any) ⇒ r
  private val _both = (l: Any, r: Any) ⇒ (l, r)
  private val _none = (l: Any, r: Any) ⇒ NotUsed

  private val _leftAsync = (l: Any, r: Any) ⇒ Future.successful(l)
  private val _rightAsync = (l: Any, r: Any) ⇒ Future.successful(r)
  private val _bothAsync = (l: Any, r: Any) ⇒ Future.successful((l, r))
  private val _noneAsync = (l: Any, r: Any) ⇒ Future.successful(NotUsed)

  def left[L, R]: (L, R) ⇒ L = _left.asInstanceOf[(L, R) ⇒ L]
  def right[L, R]: (L, R) ⇒ R = _right.asInstanceOf[(L, R) ⇒ R]
  def both[L, R]: (L, R) ⇒ (L, R) = _both.asInstanceOf[(L, R) ⇒ (L, R)]
  def none[L, R]: (L, R) ⇒ NotUsed = _none.asInstanceOf[(L, R) ⇒ NotUsed]

  def leftAsync[L, R]: (L, R) ⇒ Future[L] =
    _leftAsync.asInstanceOf[(L, R) ⇒ Future[L]]

  def rightAsync[L, R]: (L, R) ⇒ Future[R] =
    _rightAsync.asInstanceOf[(L, R) ⇒ Future[R]]

  def bothAsync[L, R]: (L, R) ⇒ Future[(L, R)] =
    _bothAsync.asInstanceOf[(L, R) ⇒ Future[(L, R)]]

  def noneAsync[L, R]: (L, R) ⇒ Future[NotUsed] =
    _noneAsync.asInstanceOf[(L, R) ⇒ Future[NotUsed]]
}
