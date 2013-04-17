/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.implicitConversions

import scala.collection.immutable
import scala.collection.GenTraversableOnce
import akka.actor.Address
import MemberStatus._

/**
 * Represents the address, current status, and roles of a cluster member node.
 *
 * Note: `hashCode` and `equals` are solely based on the underlying `Address`, not its `MemberStatus`
 * and roles.
 */
@SerialVersionUID(1L)
class Member private[cluster] (
  /** INTERNAL API **/
  private[cluster] val uniqueAddress: UniqueAddress,
  val status: MemberStatus,
  val roles: Set[String]) extends Serializable {

  def address: Address = uniqueAddress.address

  override def hashCode = uniqueAddress.##
  override def equals(other: Any) = other match {
    case m: Member ⇒ uniqueAddress == m.uniqueAddress
    case _         ⇒ false
  }
  override def toString = s"{Member(address = ${address}, status = ${status})"

  def hasRole(role: String): Boolean = roles.contains(role)

  /**
   * Java API
   */
  def getRoles: java.util.Set[String] =
    scala.collection.JavaConverters.setAsJavaSetConverter(roles).asJava

  def copy(status: MemberStatus): Member = {
    val oldStatus = this.status
    if (status == oldStatus) this
    else {
      require(allowedTransitions(oldStatus)(status),
        s"Invalid member status transition [ ${this} -> ${status}]")
      new Member(uniqueAddress, status, roles)
    }
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
    new Member(uniqueAddress, Joining, roles)

  /**
   * INTERNAL API
   */
  private[cluster] def removed(node: UniqueAddress): Member = new Member(node, Removed, Set.empty)

  /**
   * `Address` ordering type class, sorts addresses by host and port.
   */
  implicit val addressOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
    // cluster node identifier is the host and port of the address; protocol and system is assumed to be the same
    if (a.host != b.host) a.host.getOrElse("").compareTo(b.host.getOrElse("")) < 0
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
      case _                    ⇒ ordering.compare(a, b) <= 0
    }
  }

  /**
   * `Member` ordering type class, sorts members by host and port.
   */
  implicit val ordering: Ordering[Member] = new Ordering[Member] {
    def compare(a: Member, b: Member): Int = {
      val result = addressOrdering.compare(a.address, b.address)
      if (result == 0) {
        val aUid = a.uniqueAddress.uid
        val bUid = b.uniqueAddress.uid
        if (aUid < bUid) -1 else if (aUid == bUid) 0 else 1
      } else
        result
    }
  }

  def pickHighestPriority(a: Set[Member], b: Set[Member]): Set[Member] = {
    // group all members by Address => Seq[Member]
    val groupedByAddress = (a.toSeq ++ b.toSeq).groupBy(_.uniqueAddress)
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
abstract class MemberStatus

object MemberStatus {
  @SerialVersionUID(1L) case object Joining extends MemberStatus
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
      Joining -> Set(Up, Down, Removed),
      Up -> Set(Leaving, Down, Removed),
      Leaving -> Set(Exiting, Down, Removed),
      Down -> Set(Removed),
      Exiting -> Set(Removed, Down),
      Removed -> Set.empty[MemberStatus])
}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[cluster] case class UniqueAddress(address: Address, uid: Int)
