/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ Address, AddressFromURIString }
import akka.testkit.AkkaSpec
import java.net.InetSocketAddress
import scala.collection.immutable.SortedSet

class MemberOrderingSpec extends AkkaSpec {
  import Member.ordering
  import Member.addressOrdering
  import MemberStatus._

  "An Ordering[Member]" must {

    "order non-exiting members by host:port" in {
      val members = SortedSet.empty[Member] +
        Member(AddressFromURIString("akka://sys@darkstar:1112"), Up) +
        Member(AddressFromURIString("akka://sys@darkstar:1113"), Joining) +
        Member(AddressFromURIString("akka://sys@darkstar:1111"), Up)

      val seq = members.toSeq
      seq.size must equal(3)
      seq(0) must equal(Member(AddressFromURIString("akka://sys@darkstar:1111"), Up))
      seq(1) must equal(Member(AddressFromURIString("akka://sys@darkstar:1112"), Up))
      seq(2) must equal(Member(AddressFromURIString("akka://sys@darkstar:1113"), Joining))
    }

    "order exiting members by last" in {
      val members = SortedSet.empty[Member] +
        Member(AddressFromURIString("akka://sys@darkstar:1112"), Exiting) +
        Member(AddressFromURIString("akka://sys@darkstar:1113"), Up) +
        Member(AddressFromURIString("akka://sys@darkstar:1111"), Joining)

      val seq = members.toSeq
      seq.size must equal(3)
      seq(0) must equal(Member(AddressFromURIString("akka://sys@darkstar:1111"), Joining))
      seq(1) must equal(Member(AddressFromURIString("akka://sys@darkstar:1113"), Up))
      seq(2) must equal(Member(AddressFromURIString("akka://sys@darkstar:1112"), Exiting))
    }

    "order multiple exiting members by last but internally by host:port" in {
      val members = SortedSet.empty[Member] +
        Member(AddressFromURIString("akka://sys@darkstar:1112"), Exiting) +
        Member(AddressFromURIString("akka://sys@darkstar:1113"), Leaving) +
        Member(AddressFromURIString("akka://sys@darkstar:1111"), Up) +
        Member(AddressFromURIString("akka://sys@darkstar:1110"), Exiting)

      val seq = members.toSeq
      seq.size must equal(4)
      seq(0) must equal(Member(AddressFromURIString("akka://sys@darkstar:1111"), Up))
      seq(1) must equal(Member(AddressFromURIString("akka://sys@darkstar:1113"), Leaving))
      seq(2) must equal(Member(AddressFromURIString("akka://sys@darkstar:1110"), Exiting))
      seq(3) must equal(Member(AddressFromURIString("akka://sys@darkstar:1112"), Exiting))
    }
  }

  "An Ordering[Address]" must {

    "order addresses by port" in {
      val addresses = SortedSet.empty[Address] +
        AddressFromURIString("akka://sys@darkstar:1112") +
        AddressFromURIString("akka://sys@darkstar:1113") +
        AddressFromURIString("akka://sys@darkstar:1110") +
        AddressFromURIString("akka://sys@darkstar:1111")

      val seq = addresses.toSeq
      seq.size must equal(4)
      seq(0) must equal(AddressFromURIString("akka://sys@darkstar:1110"))
      seq(1) must equal(AddressFromURIString("akka://sys@darkstar:1111"))
      seq(2) must equal(AddressFromURIString("akka://sys@darkstar:1112"))
      seq(3) must equal(AddressFromURIString("akka://sys@darkstar:1113"))
    }

    "order addresses by hostname" in {
      val addresses = SortedSet.empty[Address] +
        AddressFromURIString("akka://sys@darkstar2:1110") +
        AddressFromURIString("akka://sys@darkstar1:1110") +
        AddressFromURIString("akka://sys@darkstar3:1110") +
        AddressFromURIString("akka://sys@darkstar0:1110")

      val seq = addresses.toSeq
      seq.size must equal(4)
      seq(0) must equal(AddressFromURIString("akka://sys@darkstar0:1110"))
      seq(1) must equal(AddressFromURIString("akka://sys@darkstar1:1110"))
      seq(2) must equal(AddressFromURIString("akka://sys@darkstar2:1110"))
      seq(3) must equal(AddressFromURIString("akka://sys@darkstar3:1110"))
    }

    "order addresses by hostname and port" in {
      val addresses = SortedSet.empty[Address] +
        AddressFromURIString("akka://sys@darkstar2:1110") +
        AddressFromURIString("akka://sys@darkstar0:1111") +
        AddressFromURIString("akka://sys@darkstar2:1111") +
        AddressFromURIString("akka://sys@darkstar0:1110")

      val seq = addresses.toSeq
      seq.size must equal(4)
      seq(0) must equal(AddressFromURIString("akka://sys@darkstar0:1110"))
      seq(1) must equal(AddressFromURIString("akka://sys@darkstar0:1111"))
      seq(2) must equal(AddressFromURIString("akka://sys@darkstar2:1110"))
      seq(3) must equal(AddressFromURIString("akka://sys@darkstar2:1111"))
    }
  }
}
