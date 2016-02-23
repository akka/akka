/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.util

/**
 * Phantom type providing implicit evidence that a given type is a Tuple or Unit.
 */
sealed trait Tuple[T]

object Tuple {
  /**
   * Used to provide "is-Tuple" evidence where we know that a given value must be a tuple.
   */
  def yes[T]: Tuple[T] = null

  implicit def forNothing[A]: Tuple[Nothing] = null
  implicit def forUnit[A]: Tuple[Unit] = null
  implicit def forTuple1[A]: Tuple[Tuple1[A]] = null
  implicit def forTuple2[A, B]: Tuple[(A, B)] = null
  implicit def forTuple3[A, B, C]: Tuple[(A, B, C)] = null
  implicit def forTuple4[A, B, C, D]: Tuple[(A, B, C, D)] = null
  implicit def forTuple5[A, B, C, D, E]: Tuple[(A, B, C, D, E)] = null
  implicit def forTuple6[A, B, C, D, E, F]: Tuple[(A, B, C, D, E, F)] = null
  implicit def forTuple7[A, B, C, D, E, F, G]: Tuple[(A, B, C, D, E, F, G)] = null
  implicit def forTuple8[A, B, C, D, E, F, G, H]: Tuple[(A, B, C, D, E, F, G, H)] = null
  implicit def forTuple9[A, B, C, D, E, F, G, H, I]: Tuple[(A, B, C, D, E, F, G, H, I)] = null
  implicit def forTuple10[A, B, C, D, E, F, G, H, I, J]: Tuple[(A, B, C, D, E, F, G, H, I, J)] = null
  implicit def forTuple11[A, B, C, D, E, F, G, H, I, J, K]: Tuple[(A, B, C, D, E, F, G, H, I, J, K)] = null
  implicit def forTuple12[A, B, C, D, E, F, G, H, I, J, K, L]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L)] = null
  implicit def forTuple13[A, B, C, D, E, F, G, H, I, J, K, L, M]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M)] = null
  implicit def forTuple14[A, B, C, D, E, F, G, H, I, J, K, L, M, N]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] = null
  implicit def forTuple15[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] = null
  implicit def forTuple16[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] = null
  implicit def forTuple17[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] = null
  implicit def forTuple18[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] = null
  implicit def forTuple19[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] = null
  implicit def forTuple20[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] = null
  implicit def forTuple21[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] = null
  implicit def forTuple22[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]: Tuple[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] = null
}
