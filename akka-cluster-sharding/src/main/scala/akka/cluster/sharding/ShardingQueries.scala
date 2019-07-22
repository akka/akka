/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.reflect.ClassTag

import akka.annotation.InternalApi

/** INTERNAL API */
@InternalApi
private[sharding] object ShardingQueries {

  /**
   * INTERNAL API
   * The result of a group query and metadata.
   *
   * @param timedout the number of non-responses within the configured timeout, which
   *                     could be indicative of several states, e.g. still in initialization,
   *                     restart, heavily loaded and busy, where returning a simple zero is
   *                     not indicative of the reason
   * @param responses the number of responses received from the query
   * @param total the total number of shards tracked versus a possible subset
   * @param queried the number of shards queried, which could equal the total or be a
   *                subset if this was a retry of those that timed out
   * @tparam A
   * @tparam B
   */
  @InternalApi
  private[sharding] final case class ShardsQueryResult[A, B](
      timedout: Seq[A],
      responses: Seq[B],
      total: Int,
      queried: Int) {

    /** Returns true if all in context were queried and all were unresponsive within the timeout. */
    def isTotalFailed: Boolean = timedout.size == total

    /** Returns true if this was from a subset query and all were unresponsive within the timeout. */
    def isAllSubsetFailed: Boolean = queried < total && timedout.size == queried

    override val toString: String = {
      val unresponsive = timedout.size
      if (isTotalFailed || isAllSubsetFailed) {
        s"All $unresponsive ${if (isAllSubsetFailed) "of subset" else ""} queried were unresponsive."
      } else {
        s"Queried $queried: ${responses.size} responsive, $unresponsive unresponsive within the timeout."
      }
    }
  }
  private[sharding] object ShardsQueryResult {

    /**
     * @param ps the partitioned results of actors queried that did not reply by
     *           the timeout and those that did
     * @param total the total number of actors tracked versus a possible subset
     * @tparam A
     * @tparam B
     */
    def apply[A: ClassTag, B: ClassTag](ps: Seq[Either[A, B]], total: Int): ShardsQueryResult[A, B] = {
      val (t, r) = partition(ps)(identity)
      ShardsQueryResult(t, r, total, ps.size)
    }

    def partition[T, A, B](ps: Seq[T])(f: T => Either[A, B]): (Seq[A], Seq[B]) = {
      val (a, b) = ps.foldLeft((Nil: Seq[A], Nil: Seq[B]))((xs, y) => prepend(xs, f(y)))
      (a.reverse, b.reverse)
    }

    def prepend[A, B](acc: (Seq[A], Seq[B]), next: Either[A, B]): (Seq[A], Seq[B]) =
      next match {
        case Left(l)  => (l +: acc._1, acc._2)
        case Right(r) => (acc._1, r +: acc._2)
      }
  }
}
