/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Address
import MemberStatus._

import scala.runtime.AbstractFunction2

/**
 * Represents the address, current status, and roles of a cluster member node.
 *
 * Note: `hashCode` and `equals` are solely based on the underlying `Address`, not its `MemberStatus`
 * and roles.
 */
@SerialVersionUID(1L)
class Member private[cluster] (
  val uniqueAddress:             UniqueAddress,
  private[cluster] val upNumber: Int, // INTERNAL API
  val status:                    MemberStatus,
  val roles:                     Set[String]) extends Serializable {

  def address: Address = uniqueAddress.address

  override def hashCode = uniqueAddress.##
  override def equals(other: Any) = other match {
    case m: Member ⇒ uniqueAddress == m.uniqueAddress
    case _         ⇒ false
  }
  override def toString = s"Member(address = ${address}, status = ${status})"

  def hasRole(role: String): Boolean = roles.contains(role)

  /**
   * Java API
   */
  def getRoles: java.util.Set[String] =
    scala.collection.JavaConverters.setAsJavaSetConverter(roles).asJava

  /**
   * Is this member older, has been part of cluster longer, than another
   * member. It is only correct when comparing two existing members in a
   * cluster. A member that joined after removal of another member may be
   * considered older than the removed member.
   */
  def isOlderThan(other: Member): Boolean =
    if (upNumber == other.upNumber)
      Member.addressOrdering.compare(address, other.address) < 0
    else
      upNumber < other.upNumber

  def copy(status: MemberStatus): Member = {
    val oldStatus = this.status
    if (status == oldStatus) this
    else {
      require(
        allowedTransitions(oldStatus)(status),
        s"Invalid member status transition [ ${this} -> ${status}]")
      new Member(uniqueAddress, upNumber, status, roles)
    }
  }

  def copyUp(upNumber: Int): Member = {
    new Member(uniqueAddress, upNumber, status, roles).copy(Up)
  }
}

/**
 * Module with factory and ordering methods for Member instances.
 */
object Member {

  val none = Set.empty[Member]

  /**
   * INTERNAL API
   * Create a new member with status Joining.
   */
  private[cluster] def apply(uniqueAddress: UniqueAddress, roles: Set[String]): Member =
    new Member(uniqueAddress, Int.MaxValue, Joining, roles)

  /**
   * INTERNAL API
   */
  private[cluster] def removed(node: UniqueAddress): Member = new Member(node, Int.MaxValue, Removed, Set.empty)

  /**
   * `Address` ordering type class, sorts addresses by host and port.
   */
  implicit val addressOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
    // cluster node identifier is the host and port of the address; protocol and system is assumed to be the same
    if (a eq b) false
    else if (a.host != b.host) a.host.getOrElse("").compareTo(b.host.getOrElse("")) < 0
    else if (a.port != b.port) a.port.getOrElse(0) < b.port.getOrElse(0)
    else false
  }

  /**
   * INTERNAL API
   * Orders the members by their address except that members with status
   * Joining, Exiting and Down are ordered last (in that order).
   */
  private[cluster] val leaderStatusOrdering: Ordering[Member] = Ordering.fromLessThan[Member] { (a, b) ⇒
    (a.status, b.status) match {
      case (as, bs) if as == bs ⇒ ordering.compare(a, b) <= 0
      case (Down, _)            ⇒ false
      case (_, Down)            ⇒ true
      case (Exiting, _)         ⇒ false
      case (_, Exiting)         ⇒ true
      case (Joining, _)         ⇒ false
      case (_, Joining)         ⇒ true
      case (WeaklyUp, _)        ⇒ false
      case (_, WeaklyUp)        ⇒ true
      case _                    ⇒ ordering.compare(a, b) <= 0
    }
  }

  /**
   * `Member` ordering type class, sorts members by host and port.
   */
  implicit val ordering: Ordering[Member] = new Ordering[Member] {
    def compare(a: Member, b: Member): Int = {
      a.uniqueAddress compare b.uniqueAddress
    }
  }

  /**
   * Sort members by age, i.e. using [[Member#isOlderThan]].
   */
  val ageOrdering: Ordering[Member] = Ordering.fromLessThan[Member] {
    (a, b) ⇒ a.isOlderThan(b)
  }

  def pickHighestPriority(a: Set[Member], b: Set[Member]): Set[Member] = {
    // group all members by Address => Seq[Member]
    val groupedByAddress = (a.toSeq ++ b.toSeq).groupBy(_.uniqueAddress)
    // pick highest MemberStatus
    (Member.none /: groupedByAddress) {
      case (acc, (_, members)) ⇒
        if (members.size == 2) acc + members.reduceLeft(highestPriorityOf)
        else {
          val m = members.head
          if (Gossip.removeUnreachableWithMemberStatus(m.status)) acc // removed
          else acc + m
        }
    }
  }

  /**
   * Picks the Member with the highest "priority" MemberStatus.
   */
  def highestPriorityOf(m1: Member, m2: Member): Member = {
    if (m1.status == m2.status)
      // preserve the oldest in case of different upNumber
      if (m1.isOlderThan(m2)) m1 else m2
    else (m1.status, m2.status) match {
      case (Removed, _)  ⇒ m1
      case (_, Removed)  ⇒ m2
      case (Down, _)     ⇒ m1
      case (_, Down)     ⇒ m2
      case (Exiting, _)  ⇒ m1
      case (_, Exiting)  ⇒ m2
      case (Leaving, _)  ⇒ m1
      case (_, Leaving)  ⇒ m2
      case (Joining, _)  ⇒ m2
      case (_, Joining)  ⇒ m1
      case (WeaklyUp, _) ⇒ m2
      case (_, WeaklyUp) ⇒ m1
      case (Up, Up)      ⇒ m1
    }
  }

}

/**
 * Defines the current status of a cluster member node
 *
 * Can be one of: Joining, WeaklyUp, Up, Leaving, Exiting and Down and Removed.
 */
sealed abstract class MemberStatus

object MemberStatus {
  @SerialVersionUID(1L) case object Joining extends MemberStatus
  /**
   * WeaklyUp is an EXPERIMENTAL feature and is subject to change until
   * it has received more real world testing.
   */
  @SerialVersionUID(1L) case object WeaklyUp extends MemberStatus
  @SerialVersionUID(1L) case object Up extends MemberStatus
  @SerialVersionUID(1L) case object Leaving extends MemberStatus
  @SerialVersionUID(1L) case object Exiting extends MemberStatus
  @SerialVersionUID(1L) case object Down extends MemberStatus
  @SerialVersionUID(1L) case object Removed extends MemberStatus

  /**
   * Java API: retrieve the “joining” status singleton
   */
  def joining: MemberStatus = Joining

  /**
   * Java API: retrieve the “weaklyUp” status singleton.
   * WeaklyUp is an EXPERIMENTAL feature and is subject to change until
   * it has received more real world testing.
   */
  def weaklyUp: MemberStatus = WeaklyUp

  /**
   * Java API: retrieve the “up” status singleton
   */
  def up: MemberStatus = Up

  /**
   * Java API: retrieve the “leaving” status singleton
   */
  def leaving: MemberStatus = Leaving

  /**
   * Java API: retrieve the “exiting” status singleton
   */
  def exiting: MemberStatus = Exiting

  /**
   * Java API: retrieve the “down” status singleton
   */
  def down: MemberStatus = Down

  /**
   * Java API: retrieve the “removed” status singleton
   */
  def removed: MemberStatus = Removed

  /**
   * INTERNAL API
   */
  private[cluster] val allowedTransitions: Map[MemberStatus, Set[MemberStatus]] =
    Map(
      Joining → Set(WeaklyUp, Up, Down, Removed),
      WeaklyUp → Set(Up, Down, Removed),
      Up → Set(Leaving, Down, Removed),
      Leaving → Set(Exiting, Down, Removed),
      Down → Set(Removed),
      Exiting → Set(Removed, Down),
      Removed → Set.empty[MemberStatus])
}

object UniqueAddress extends AbstractFunction2[Address, Int, UniqueAddress] {

  // for binary compatibility
  @deprecated("Use Long UID apply instead", since = "2.4.11")
  def apply(address: Address, uid: Int) = new UniqueAddress(address, uid.toLong)

}

/**
 * Member identifier consisting of address and random `uid`.
 * The `uid` is needed to be able to distinguish different
 * incarnations of a member with same hostname and port.
 */
@SerialVersionUID(1L)
final case class UniqueAddress(address: Address, longUid: Long) extends Ordered[UniqueAddress] {

  override def hashCode = java.lang.Long.hashCode(longUid)

  def compare(that: UniqueAddress): Int = {
    val result = Member.addressOrdering.compare(this.address, that.address)
    if (result == 0) if (this.longUid < that.longUid) -1 else if (this.longUid == that.longUid) 0 else 1
    else result
  }

  // for binary compatibility

  @deprecated("Use Long UID constructor instead", since = "2.4.11")
  def this(address: Address, uid: Int) = this(address, uid.toLong)

  @deprecated("Use longUid instead", since = "2.4.11")
  def uid = longUid.toInt

  /**
   * For binary compatibility
   * Stops `copy(Address, Long)` copy from being generated, use `apply` instead.
   */
  @deprecated("Use Long UID constructor instead", since = "2.4.11")
  def copy(address: Address = address, uid: Int = uid) = new UniqueAddress(address, uid)

}
