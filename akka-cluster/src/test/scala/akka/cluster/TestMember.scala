/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.actor.Address

object TestMember {
  def apply(address: Address, status: MemberStatus): Member =
    apply(address, status, Set.empty)

  def apply(address: Address, status: MemberStatus, roles: Set[String], dataCenter: ClusterSettings.DataCenter = ClusterSettings.DefaultDataCenter): Member =
    new Member(UniqueAddress(address, 0L), Int.MaxValue, status, roles + (ClusterSettings.DcRolePrefix + dataCenter))
}
