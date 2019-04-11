/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Address

object TestMember {
  def apply(address: Address, status: MemberStatus): Member =
    apply(address, status, Set.empty[String])

  def apply(address: Address, status: MemberStatus, upNumber: Int, dc: ClusterSettings.DataCenter): Member =
    apply(address, status, Set.empty, dc, upNumber)

  def apply(
      address: Address,
      status: MemberStatus,
      roles: Set[String],
      dataCenter: ClusterSettings.DataCenter = ClusterSettings.DefaultDataCenter,
      upNumber: Int = Int.MaxValue): Member =
    withUniqueAddress(UniqueAddress(address, 0L), status, roles, dataCenter, upNumber)

  def withUniqueAddress(
      uniqueAddress: UniqueAddress,
      status: MemberStatus,
      roles: Set[String],
      dataCenter: ClusterSettings.DataCenter,
      upNumber: Int = Int.MaxValue): Member =
    new Member(uniqueAddress, upNumber, status, roles + (ClusterSettings.DcRolePrefix + dataCenter))
}
