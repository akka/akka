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
   * @param failed the queries to shards that failed within the configured timeout. This
   *                     could be indicative of several states, e.g. still in initialization,
   *                     restart, heavily loaded and busy, where returning zero entities is
   *                     not indicative of the reason
   * @param responses the responses received from the query
   * @param total the total number of shards tracked versus a possible subset
   * @param queried the number of shards queried, which could equal the total or be a
   *                subset if this was a retry of those that timed out
   * @tparam A
   * @tparam B
   */
  final case class ShardsQueryResult[A, B](failed: Set[A], responses: Seq[B], total: Int, queried: Int) {

    /** Returns true if there was anything to query. */
    private val nonEmpty: Boolean = total > 0 && queried > 0

    /** Returns true if there was anything to query, all were queried and all were unresponsive within the timeout. */
    def isTotalFailed: Boolean = nonEmpty && failed.size == total

    /** Returns true if there was a subset to query and all in that subset were unresponsive within the timeout. */
    def isAllSubsetFailed: Boolean = nonEmpty && queried < total && failed.size == queried

    override val toString: String = {
      if (total == 0)
        s"Shard region had zero shards to gather metadata from."
      else if (isTotalFailed || isAllSubsetFailed) {
        s"All [${failed.size}] shards ${if (isAllSubsetFailed) "of subset" else ""} queried were unresponsive."
      } else {
        s"Queried [$queried] shards of [$total]: responsive [${responses.size}], unresponsive [${failed.size}] within the timeout."
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
      ShardsQueryResult(t.toSet, r, total, ps.size)
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
