/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.annotation.InternalApi

/** INTERNAL API */
@InternalApi
private[sharding] object ShardingQueries {

  private def func[T] = (t: T) => t

  def separateE[A, B](ps: Seq[Either[A, B]]): (Seq[A], Seq[B]) =
    separate(ps)(func)

  def separate[T, A, B](ps: Seq[T])(f: T => Either[A, B]): (Seq[A], Seq[B]) = {
    val (a, b) = ps.foldLeft((Nil: Seq[A], Nil: Seq[B]))((xs, y) => prependEither(xs, f(y)))
    (a.reverse, b.reverse)
  }

  def prependEither[A, B](acc: (Seq[A], Seq[B]), next: Either[A, B]): (Seq[A], Seq[B]) =
    next match {
      case Left(l)  => (l +: acc._1, acc._2)
      case Right(r) => (acc._1, r +: acc._2)
    }

  /**
   * The result metadata of a group query, which can be applied to any granularity of sharding:
   * regions, shards, entities.
   *
   * @param total the total number of actors tracked versus a possible subset
   * @param queried the number of actors queried, which could equal the total or be a
   *                subset if a retry of failures only
   * @param unresponsive the number of non-responses within the configured timeout, which
   *                     could be indicative of several states, e.g. still in initialization,
   *                     restart, heavily loaded and busy, where returning a simple zero is
   *                     not indicative of the reason
   * @param responsive the number of responses received from the query
   */
  final case class Metadata(total: Int, queried: Int, unresponsive: Int, responsive: Int) {

    /** Returns true if all in context were queried and all were unresponsive within the timeout. */
    def isTotalFailed: Boolean = unresponsive == total

    /** Returns true if this was from a subset query and all were unresponsive within the timeout. */
    def isAllSubsetFailed: Boolean = queried < total && unresponsive == queried
    def unresponsiveNonEmpty: Boolean = unresponsive > 0

    override val toString: String = {
      if (isTotalFailed || isAllSubsetFailed) {
        s"All $unresponsive ${if (isAllSubsetFailed) "of subset" else ""} queried were unresponsive."
      } else {
        s"Queried $queried: $responsive responsive $unresponsive unresponsive within the timeout."
      }
    }
  }

  object Metadata {

    /**
     * @param total the total number of actors tracked versus a possible subset
     * @param u the results of actors queried that did not reply by the timeout
     * @param t the results of actors queried that did reply
     */
    def apply(total: Int, u: Seq[_], t: Seq[_]): Metadata = {
      val queried = u.size + t.size
      Metadata(total, queried, u.size, t.size)
    }
  }
}
