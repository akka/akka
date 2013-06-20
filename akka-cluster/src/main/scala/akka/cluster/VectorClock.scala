/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.AkkaException

import System.{ currentTimeMillis ⇒ newTimestamp }
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.annotation.tailrec

/**
 * Trait to be extended by classes that wants to be versioned using a VectorClock.
 */
trait Versioned[T] {
  def version: VectorClock
  def :+(node: VectorClock.Node): T
}

/**
 * Utility methods for comparing Versioned instances.
 */
object Versioned {

  /**
   * The result of comparing two Versioned objects.
   * Either:
   * {{{
   *   1) v1 is BEFORE v2               => Before
   *   2) v1 is AFTER t2                => After
   *   3) v1 happens CONCURRENTLY to v2 => Concurrent
   * }}}
   */
  sealed trait Ordering
  case object Before extends Ordering
  case object After extends Ordering
  case object Concurrent extends Ordering

  /**
   * Returns or 'Ordering' for the two 'Versioned' instances.
   */
  def compare[T <: Versioned[T]](versioned1: Versioned[T], versioned2: Versioned[T]): Ordering =
    versioned1.version tryCompareTo versioned2.version match {
      case VectorClock.Concurrent ⇒ Concurrent
      case VectorClock.LessThan   ⇒ Before
      case _                      ⇒ After
    }

  /**
   * Returns the Versioned that have the latest version.
   */
  def latestVersionOf[T <: Versioned[T]](versioned1: T, versioned2: T): T =
    compare(versioned1, versioned2) match {
      case Concurrent | Before ⇒ versioned2
      case After               ⇒ versioned1
    }
}

/**
 * VectorClock module with helper classes and methods.
 *
 * Based on code from the 'vlock' VectorClock library by Coda Hale.
 */
object VectorClock {

  /**
   * Hash representation of a versioned node name.
   */
  sealed trait Node extends Serializable {
    def hash: String
  }

  object Node {
    @SerialVersionUID(1L)
    private case class NodeImpl(name: String) extends Node {
      override def toString(): String = "Node(" + name + ")"
      override def hash: String = name
      override def hashCode = name.hashCode
    }

    def apply(name: String): Node = NodeImpl(hash(name))

    def fromHash(hash: String): Node = NodeImpl(hash)

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
    private[this] val counter = new AtomicLong(newTimestamp)

    final val zero: Timestamp = Timestamp(0L)

    def apply(): Timestamp = {
      @tailrec def generateNext(): Long = {
        val last = counter.get
        val current = newTimestamp
        val next = if (current > last) current else last + 1
        if (counter.compareAndSet(last, next)) next else generateNext()
      }
      new Timestamp(generateNext())
    }
  }

  private[akka] final val GreaterThan = Some(1)
  private[akka] final val LessThan = Some(-1)
  private[akka] final val Equal = Some(0)
  private[akka] final val Concurrent = Option.empty[Int]
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
final case class VectorClock(
  timestamp: VectorClock.Timestamp = VectorClock.Timestamp(),
  versions: Map[VectorClock.Node, VectorClock.Timestamp] = Map.empty[VectorClock.Node, VectorClock.Timestamp])
  extends PartiallyOrdered[VectorClock] {

  import VectorClock._

  /**
   * Increment the version for the node passed as argument. Returns a new VectorClock.
   */
  def :+(node: Node): VectorClock = copy(versions = versions + (node -> Timestamp()))

  /**
   * Returns true if <code>this</code> and <code>that</code> are concurrent else false.
   */
  def <>(that: VectorClock): Boolean = tryCompareTo(that) == Concurrent

  /**
   * Returns true if this VectorClock has the same history as the 'that' VectorClock else false.
   */
  def ==(that: VectorClock): Boolean = versions == that.versions

  /**
   * For the 'PartiallyOrdered' trait, to allow natural comparisons using <, > and ==.
   * <p/>
   * Compare two vector clocks. The outcomes will be one of the following:
   * <p/>
   * {{{
   *   1. Clock 1 is BEFORE (>)      Clock 2 if there exists an i such that c1(i) <= c2(i) and there does not exist a j such that c1(j) > c2(j).
   *   2. Clock 1 is CONCURRENT (<>) to Clock 2 if there exists an i, j such that c1(i) < c2(i) and c1(j) > c2(j).
   *   3. Clock 1 is AFTER (<)       Clock 2 otherwise.
   * }}}
   */
  def tryCompareTo[V >: VectorClock <% PartiallyOrdered[V]](vclock: V): Option[Int] =
    vclock match {
      case other: VectorClock ⇒
        def newerOrSameIn(version: (Node, Timestamp), versions: Map[Node, Timestamp]): Boolean =
          version._2 <= versions(version._1)
        if (versions == other.versions) Equal
        else if (versions.forall(newerOrSameIn(_, other.versions.withDefaultValue(Timestamp.zero)))) LessThan
        else if (other.versions.forall(newerOrSameIn(_, versions.withDefaultValue(Timestamp.zero)))) GreaterThan
        else Concurrent
      case _ ⇒ Concurrent
    }

  /**
   * Merges this VectorClock with another VectorClock. E.g. merges its versioned history.
   */
  def merge(that: VectorClock): VectorClock = {
    val mergedVersions = mutable.Map.empty[VectorClock.Node, VectorClock.Timestamp] ++ that.versions
    val cmpVersions = mergedVersions.withDefaultValue(Timestamp.zero)
    var modifications = false
    for ((node, time) ← versions) {
      val mergedVersionsCurrentTime = cmpVersions(node)
      if (time != mergedVersionsCurrentTime) {
        mergedVersions(node) = time max mergedVersionsCurrentTime
        modifications = true
      }
    }
    val mergeResult = if (modifications) mergedVersions.toMap else that.versions
    VectorClock(timestamp, mergeResult)
  }

  override def toString = versions.map { case ((n, t)) ⇒ n + " -> " + t }.mkString("VectorClock(", ", ", ")")
}
