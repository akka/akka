/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.implicitConversions

import scala.collection.immutable.SortedSet
import scala.collection.GenTraversableOnce
import akka.actor.Address
import MemberStatus._

/**
 * Represents the address and the current status of a cluster member node.
 *
 * Note: `hashCode` and `equals` are solely based on the underlying `Address`, not its `MemberStatus`.
 */
class Member(val address: Address, val status: MemberStatus) extends ClusterMessage {
  override def hashCode = address.##
  override def equals(other: Any) = Member.unapply(this) == Member.unapply(other)
  override def toString = "Member(address = %s, status = %s)" format (address, status)
  def copy(address: Address = this.address, status: MemberStatus = this.status): Member = new Member(address, status)
}

/**
 * Module with factory and ordering methods for Member instances.
 */
object Member {

  val none = Set.empty[Member]

  /**
   * `Address` ordering type class, sorts addresses by host and port.
   */
  implicit val addressOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
    if (a.host != b.host) a.host.getOrElse("").compareTo(b.host.getOrElse("")) < 0
    else if (a.port != b.port) a.port.getOrElse(0) < b.port.getOrElse(0)
    else false
  }

  /**
   * `Member` ordering type class, sorts members by host and port with the exception that
   * it puts all members that are in MemberStatus.EXITING last.
   */
  implicit val ordering: Ordering[Member] = Ordering.fromLessThan[Member] { (a, b) ⇒
    if (a.status == Exiting && b.status != Exiting) false
    else if (a.status != Exiting && b.status == Exiting) true
    else addressOrdering.compare(a.address, b.address) < 0
  }

  def apply(address: Address, status: MemberStatus): Member = new Member(address, status)

  def unapply(other: Any) = other match {
    case m: Member ⇒ Some(m.address)
    case _         ⇒ None
  }

  def pickHighestPriority(a: Set[Member], b: Set[Member]): Set[Member] = {
    // group all members by Address => Seq[Member]
    val groupedByAddress = (a.toSeq ++ b.toSeq).groupBy(_.address)
    // pick highest MemberStatus
    (Member.none /: groupedByAddress) {
      case (acc, (_, members)) ⇒ acc + members.reduceLeft(highestPriorityOf)
    }
  }

  /**
   * Picks the Member with the highest "priority" MemberStatus.
   */
  def highestPriorityOf(m1: Member, m2: Member): Member = (m1.status, m2.status) match {
    case (Removed, _)       ⇒ m1
    case (_, Removed)       ⇒ m2
    case (Down, _)          ⇒ m1
    case (_, Down)          ⇒ m2
    case (Exiting, _)       ⇒ m1
    case (_, Exiting)       ⇒ m2
    case (Leaving, _)       ⇒ m1
    case (_, Leaving)       ⇒ m2
    case (Up, Joining)      ⇒ m2
    case (Joining, Up)      ⇒ m1
    case (Joining, Joining) ⇒ m1
    case (Up, Up)           ⇒ m1
  }

}

/**
 * Defines the current status of a cluster member node
 *
 * Can be one of: Joining, Up, Leaving, Exiting and Down.
 */
sealed trait MemberStatus extends ClusterMessage {

  /**
   * Using the same notion for 'unavailable' as 'non-convergence': DOWN
   */
  def isUnavailable: Boolean = this == Down
}

object MemberStatus {
  case object Joining extends MemberStatus
  case object Up extends MemberStatus
  case object Leaving extends MemberStatus
  case object Exiting extends MemberStatus
  case object Down extends MemberStatus
  case object Removed extends MemberStatus
}