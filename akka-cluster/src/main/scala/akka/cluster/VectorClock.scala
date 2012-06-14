/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.AkkaException
import akka.event.Logging
import akka.actor.ActorSystem

import System.{ currentTimeMillis ⇒ newTimestamp }
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class VectorClockException(message: String) extends AkkaException(message)

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
  def compare[T <: Versioned[T]](versioned1: Versioned[T], versioned2: Versioned[T]): Ordering = {
    if (versioned1.version <> versioned2.version) Concurrent
    else if (versioned1.version < versioned2.version) Before
    else After
  }

  /**
   * Returns the Versioned that have the latest version.
   */
  def latestVersionOf[T <: Versioned[T]](versioned1: T, versioned2: T): T = {
    compare(versioned1, versioned2) match {
      case Concurrent ⇒ versioned2
      case Before     ⇒ versioned2
      case After      ⇒ versioned1
    }
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
  sealed trait Node extends Serializable

  object Node {
    private case class NodeImpl(name: String) extends Node {
      override def toString(): String = "Node(" + name + ")"
    }

    def apply(name: String): Node = NodeImpl(hash(name))

    private def hash(name: String): String = {
      val digester = MessageDigest.getInstance("MD5")
      digester update name.getBytes("UTF-8")
      digester.digest.map { h ⇒ "%02x".format(0xFF & h) }.mkString
    }
  }

  /**
   * Timestamp representation a unique 'Ordered' timestamp.
   */
  case class Timestamp private (time: Long) extends Ordered[Timestamp] {
    def max(other: Timestamp) = {
      if (this < other) other
      else this
    }

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
case class VectorClock(
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
  def <>(that: VectorClock): Boolean = tryCompareTo(that) == None

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
   *   1. Clock 1 is BEFORE (>)      Clock 2 if there exists an i such that c1(i) <= c(2) and there does not exist a j such that c1(j) > c2(j).
   *   2. Clock 1 is CONCURRENT (<>) to Clock 2 if there exists an i, j such that c1(i) < c2(i) and c1(j) > c2(j).
   *   3. Clock 1 is AFTER (<)       Clock 2 otherwise.
   * }}}
   */
  def tryCompareTo[V >: VectorClock <% PartiallyOrdered[V]](vclock: V): Option[Int] = {
    def compare(versions1: Map[Node, Timestamp], versions2: Map[Node, Timestamp]): Boolean = {
      versions1.forall { case ((n, t)) ⇒ t <= versions2.getOrElse(n, Timestamp.zero) } &&
        (versions1.exists { case ((n, t)) ⇒ t < versions2.getOrElse(n, Timestamp.zero) } ||
          (versions1.size < versions2.size))
    }
    vclock match {
      case VectorClock(_, otherVersions) ⇒
        if (compare(versions, otherVersions)) Some(-1)
        else if (compare(otherVersions, versions)) Some(1)
        else if (versions == otherVersions) Some(0)
        else None
      case _ ⇒ None
    }
  }

  /**
   * Merges this VectorClock with another VectorClock. E.g. merges its versioned history.
   */
  def merge(that: VectorClock): VectorClock = {
    val mergedVersions = scala.collection.mutable.Map.empty[Node, Timestamp] ++ that.versions
    for ((node, time) ← versions) mergedVersions(node) = time max mergedVersions.getOrElse(node, time)
    VectorClock(timestamp, Map.empty[Node, Timestamp] ++ mergedVersions)
  }

  override def toString = versions.map { case ((n, t)) ⇒ n + " -> " + t }.mkString("VectorClock(", ", ", ")")
}
