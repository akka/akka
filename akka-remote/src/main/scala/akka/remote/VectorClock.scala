/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.AkkaException

class VectorClockException(message: String) extends AkkaException(message)

trait Versioned {
  def version: VectorClock
}

object Versioned {
  def latestVersionOf[T <: Versioned](versioned1: T, versioned2: T): T = {
    (versioned1.version compare versioned2.version) match {
      case VectorClock.Before     ⇒ versioned2 // version 1 is BEFORE (older), use version 2
      case VectorClock.After      ⇒ versioned1 // version 1 is AFTER (newer), use version 1
      case VectorClock.Concurrent ⇒ versioned1 // can't establish a causal relationship between versions => conflict - keeping version 1
    }
  }
}

/**
 * Representation of a Vector-based clock (counting clock), inspired by Lamport logical clocks.
 *
 * Reference:
 *    1) Leslie Lamport (1978). "Time, clocks, and the ordering of events in a distributed system". Communications of the ACM 21 (7): 558-565.
 *    2) Friedemann Mattern (1988). "Virtual Time and Global States of Distributed Systems". Workshop on Parallel and Distributed Algorithms: pp. 215-226
 */
case class VectorClock(
  versions: Vector[VectorClock.Entry] = Vector.empty[VectorClock.Entry],
  timestamp: Long = System.currentTimeMillis) {
  import VectorClock._

  def compare(other: VectorClock): Ordering = VectorClock.compare(this, other)

  def increment(fingerprint: Int, timestamp: Long): VectorClock = {
    val newVersions =
      if (versions exists (entry ⇒ entry.fingerprint == fingerprint)) {
        // update existing node entry
        versions map { entry ⇒
          if (entry.fingerprint == fingerprint) entry.increment()
          else entry
        }
      } else {
        // create and append a new node entry
        versions :+ Entry(fingerprint = fingerprint)
      }
    if (newVersions.size > MaxNrOfVersions) throw new VectorClockException("Max number of versions reached")
    copy(versions = newVersions, timestamp = timestamp)
  }

  def maxVersion: Long = versions.foldLeft(1L)((max, entry) ⇒ math.max(max, entry.version))

  // FIXME Do we need to implement VectorClock.merge?
  def merge(other: VectorClock): VectorClock = {
    sys.error("Not implemented")
  }
}

/**
 * Module with helper classes and methods.
 */
object VectorClock {
  final val MaxNrOfVersions = Short.MaxValue

  /**
   * The result of comparing two vector clocks.
   * Either:
   * {{
   *   1) v1 is BEFORE v2
   *   2) v1 is AFTER t2
   *   3) v1 happens CONCURRENTLY to v2
   * }}
   */
  sealed trait Ordering
  case object Before extends Ordering
  case object After extends Ordering
  case object Concurrent extends Ordering

  /**
   * Versioned entry in a vector clock.
   */
  case class Entry(fingerprint: Int, version: Long = 1L) {
    def increment(): Entry = copy(version = version + 1L)
  }

  /**
   * Compare two vector clocks. The outcomes will be one of the following:
   * <p/>
   * 1. Clock 1 is BEFORE clock 2 if there exists an i such that c1(i) <= c(2) and there does not exist a j such that c1(j) > c2(j).
   * 2. Clock 1 is CONCURRENT to clock 2 if there exists an i, j such that c1(i) < c2(i) and c1(j) > c2(j).
   * 3. Clock 1 is AFTER clock 2 otherwise.
   *
   * @param v1 The first VectorClock
   * @param v2 The second VectorClock
   */
  def compare(v1: VectorClock, v2: VectorClock): Ordering = {
    if ((v1 eq null) || (v2 eq null)) throw new IllegalArgumentException("Can't compare null VectorClocks")

    // FIXME rewrite to functional style, now uses ugly imperative algorithm

    var v1Bigger, v2Bigger = false // We do two checks: v1 <= v2 and v2 <= v1 if both are true then
    var p1, p2 = 0

    while (p1 < v1.versions.size && p2 < v2.versions.size) {
      val ver1 = v1.versions(p1)
      val ver2 = v2.versions(p2)
      if (ver1.fingerprint == ver2.fingerprint) {
        if (ver1.version > ver2.version) v1Bigger = true
        else if (ver2.version > ver1.version) v2Bigger = true
        p1 += 1
        p2 += 1
      } else if (ver1.fingerprint > ver2.fingerprint) {
        v2Bigger = true // Since ver1 is bigger that means it is missing a version that ver2 has
        p2 += 1
      } else {
        v1Bigger = true // This means ver2 is bigger which means it is missing a version ver1 has
        p1 += 1
      }
    }

    if (p1 < v1.versions.size) v1Bigger = true
    else if (p2 < v2.versions.size) v2Bigger = true

    if (!v1Bigger && !v2Bigger) Before // This is the case where they are equal, return BEFORE arbitrarily
    else if (v1Bigger && !v2Bigger) After // This is the case where v1 is a successor clock to v2
    else if (!v1Bigger && v2Bigger) Before // This is the case where v2 is a successor clock to v1
    else Concurrent // This is the case where both clocks are parallel to one another
  }
}
