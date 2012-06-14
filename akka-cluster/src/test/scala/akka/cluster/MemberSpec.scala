/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import scala.util.Random
import scala.collection.immutable.SortedSet

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MemberSpec extends WordSpec with MustMatchers {

  "Member" must {

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
  }
}
