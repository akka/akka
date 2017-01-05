/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.actor.Address

object TestMember {
  def apply(address: Address, status: MemberStatus): Member =
    apply(address, status, Set.empty)

  def apply(address: Address, status: MemberStatus, roles: Set[String]): Member =
    new Member(UniqueAddress(address, 0), Int.MaxValue, status, roles)
}
