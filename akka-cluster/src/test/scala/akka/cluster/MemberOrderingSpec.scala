/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ Address, AddressFromURIString }
import java.net.InetSocketAddress
import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import scala.collection.immutable.SortedSet
import scala.util.Random

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MemberOrderingSpec extends WordSpec with MustMatchers {
  import Member.ordering
  import Member.addressOrdering
  import MemberStatus._

  "An Ordering[Member]" must {

    "order members by host:port" in {
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

    "be sorted by address correctly" in {
      import Member.ordering
      // sorting should be done on host and port, only
      val m1 = Member(Address("akka", "sys1", "host1", 9000), MemberStatus.Up)
      val m2 = Member(Address("akka", "sys1", "host1", 10000), MemberStatus.Up)
      val m3 = Member(Address("cluster", "sys2", "host2", 8000), MemberStatus.Up)
      val m4 = Member(Address("cluster", "sys2", "host2", 9000), MemberStatus.Up)
      val m5 = Member(Address("cluster", "sys1", "host2", 10000), MemberStatus.Up)

      val expected = IndexedSeq(m1, m2, m3, m4, m5)
      val shuffled = Random.shuffle(expected)
      shuffled.sorted must be(expected)
      (SortedSet.empty[Member] ++ shuffled).toIndexedSeq must be(expected)
    }

    "have stable equals and hashCode" in {
      val m1 = Member(Address("akka", "sys1", "host1", 9000), MemberStatus.Joining)
      val m2 = Member(Address("akka", "sys1", "host1", 9000), MemberStatus.Up)
      val m3 = Member(Address("akka", "sys1", "host1", 10000), MemberStatus.Up)

      m1 must be(m2)
      m1.hashCode must be(m2.hashCode)

      m3 must not be (m2)
      m3 must not be (m1)
    }

    "have consistent ordering and equals" in {
      val address1 = Address("akka", "sys1", "host1", 9001)
      val address2 = Address("akka", "sys1", "host1", 9002)

      val x = Member(address1, Exiting)
      val y = Member(address1, Removed)
      val z = Member(address2, Up)
      Member.ordering.compare(x, y) must be(0)
      Member.ordering.compare(x, z) must be(Member.ordering.compare(y, z))
    }

    "work with SortedSet" in {
      val address1 = Address("akka", "sys1", "host1", 9001)
      val address2 = Address("akka", "sys1", "host1", 9002)
      val address3 = Address("akka", "sys1", "host1", 9003)

      (SortedSet(Member(address1, MemberStatus.Joining)) - Member(address1, MemberStatus.Up)) must be(SortedSet.empty[Member])
      (SortedSet(Member(address1, MemberStatus.Exiting)) - Member(address1, MemberStatus.Removed)) must be(SortedSet.empty[Member])
      (SortedSet(Member(address1, MemberStatus.Up)) - Member(address1, MemberStatus.Exiting)) must be(SortedSet.empty[Member])
      (SortedSet(Member(address2, Up), Member(address3, Joining), Member(address1, MemberStatus.Exiting)) - Member(address1, MemberStatus.Removed)) must be(
        SortedSet(Member(address2, Up), Member(address3, Joining)))
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
