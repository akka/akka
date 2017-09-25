/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.actor.Address

object TestMember {
  def apply(address: Address, status: MemberStatus): Member =
    apply(address, status, Set.empty)

  def apply(address: Address, status: MemberStatus, roles: Set[String], dataCenter: ClusterSettings.DataCenter = ClusterSettings.DefaultDataCenter): Member =
    withUniqueAddress(UniqueAddress(address, 0L), status, roles, dataCenter)

  def withUniqueAddress(uniqueAddress: UniqueAddress, status: MemberStatus, roles: Set[String], dataCenter: ClusterSettings.DataCenter): Member =
    new Member(uniqueAddress, Int.MaxValue, status, roles + (ClusterSettings.DcRolePrefix + dataCenter))
}
