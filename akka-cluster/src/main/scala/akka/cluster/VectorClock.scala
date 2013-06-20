/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.AkkaException

import System.{ currentTimeMillis ⇒ newTimestamp }
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import scala.collection.immutable.TreeMap

/**
 * VectorClock module with helper classes and methods.
 *
 * Based on code from the 'vlock' VectorClock library by Coda Hale.
 */
private[cluster] object VectorClock {

  /**
   * Hash representation of a versioned node name.
   */
  type Node = String

  object Node {

    def apply(name: String): Node = hash(name)

    def fromHash(hash: String): Node = hash

    private def hash(name: String): String = {
      val digester = MessageDigest.getInstance("MD5")
      digester update name.getBytes("UTF-8")
      digester.digest.map { h ⇒ "%02x".format(0xFF & h) }.mkString
    }
  }

  /**
   * Timestamp representation a unique 'Ordered' timestamp.
   */
  @SerialVersionUID(1L)
  final case class Timestamp(time: Long) extends Ordered[Timestamp] {
    def max(other: Timestamp) = if (this < other) other else this

    def compare(other: Timestamp) = time compare other.time

    override def toString = "%016x" format time
  }

  object Timestamp {
    private val counter = new AtomicLong(newTimestamp)

    val zero: Timestamp = Timestamp(0L)

    def apply(): Timestamp = {
      var newTime: Long = 0L
      while (newTime == 0) {
        val last = counter.get
        val current = newTimestamp
        val next = if (current > last) current else last + 1
        if (counter.compareAndSet(last, next)) {
          newTime = next
        }
      }
      new Timestamp(newTime)
    }
  }

  sealed trait Ordering
  case object After extends Ordering
  case object Before extends Ordering
  case object Same extends Ordering
  case object Concurrent extends Ordering
}

/**
 * Representation of a Vector-based clock (counting clock), inspired by Lamport logical clocks.
 * {{{
 * Reference:
 *    1) Leslie Lamport (1978). "Time, clocks, and the ordering of events in a distributed system". Communications of the ACM 21 (7): 558-565.
 *    2) Friedemann Mattern (1988). "Virtual Time and Global States of Distributed Systems". Workshop on Parallel and Distributed Algorithms: pp. 215-226
 * }}}
 *
 * Based on code from the 'vlock' VectorClock library by Coda Hale.
 */
@SerialVersionUID(1L)
case class VectorClock(
  versions: TreeMap[VectorClock.Node, VectorClock.Timestamp] = TreeMap.empty[VectorClock.Node, VectorClock.Timestamp]) {

  import VectorClock._

  /**
   * Increment the version for the node passed as argument. Returns a new VectorClock.
   */
  def :+(node: Node): VectorClock = copy(versions = versions + (node -> Timestamp()))

  /**
   * Returns true if <code>this</code> and <code>that</code> are concurrent else false.
   */
  def <>(that: VectorClock): Boolean = compareTo(that) == Concurrent

  /**
   * Returns true if <code>this</code> is before <code>that</code> else false.
   */
  def <(that: VectorClock): Boolean = compareTo(that) == Before

  /**
   * Returns true if <code>this</code> is after <code>that</code> else false.
   */
  def >(that: VectorClock): Boolean = compareTo(that) == After

  /**
   * Returns true if this VectorClock has the same history as the 'that' VectorClock else false.
   */
  def ==(that: VectorClock): Boolean = versions == that.versions

  /**
   * Compare two vector clocks. The outcome will be one of the following:
   * <p/>
   * {{{
   *   1. Clock 1 is SAME (==)       as Clock 2 iff for all i c1(i) == c2(i)
   *   2. Clock 1 is BEFORE (<)      Clock 2 iff for all i c1(i) <= c2(i) and there exist a j such that c1(j) < c2(j)
   *   3. Clock 1 is AFTER (>)       Clock 2 iff for all i c1(i) >= c2(i) and there exist a j such that c1(j) > c2(j).
   *   4. Clock 1 is CONCURRENT (<>) to Clock 2 otherwise.
   * }}}
   */
  def compareTo(that: VectorClock): Ordering = {
    def olderOrSameIn(version: Pair[Node, Timestamp], versions: Map[Node, Timestamp]): Boolean = {
      version match { case (n, t) ⇒ t <= versions(n) }
    }

    if (versions == that.versions) Same
    else if (versions.forall(olderOrSameIn(_, that.versions.withDefaultValue(Timestamp.zero)))) Before
    else if (that.versions.forall(olderOrSameIn(_, versions.withDefaultValue(Timestamp.zero)))) After
    else Concurrent
  }

  /**
   * Merges this VectorClock with another VectorClock. E.g. merges its versioned history.
   */
  def merge(that: VectorClock): VectorClock = {
    var mergedVersions = that.versions
    for ((node, time) ← versions) {
      val mergedVersionsCurrentTime = mergedVersions.getOrElse(node, Timestamp.zero)
      if (time > mergedVersionsCurrentTime)
        mergedVersions = mergedVersions.updated(node, time)
    }
    VectorClock(mergedVersions)
  }

  override def toString = versions.map { case ((n, t)) ⇒ n + " -> " + t }.mkString("VectorClock(", ", ", ")")
}
