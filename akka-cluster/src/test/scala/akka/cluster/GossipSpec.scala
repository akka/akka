/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import scala.collection.immutable.SortedSet

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class GossipSpec extends WordSpec with MustMatchers {

  "A Gossip" must {

    "merge members by status priority" in {
      import MemberStatus._
      val a1 = Member(Address("akka", "sys", "a", 2552), Up)
      val a2 = Member(Address("akka", "sys", "a", 2552), Joining)
      val b1 = Member(Address("akka", "sys", "b", 2552), Up)
      val b2 = Member(Address("akka", "sys", "b", 2552), Removed)
      val c1 = Member(Address("akka", "sys", "c", 2552), Leaving)
      val c2 = Member(Address("akka", "sys", "c", 2552), Up)
      val d1 = Member(Address("akka", "sys", "d", 2552), Leaving)
      val d2 = Member(Address("akka", "sys", "d", 2552), Removed)

      val g1 = Gossip(members = SortedSet(a1, b1, c1, d1))
      val g2 = Gossip(members = SortedSet(a2, b2, c2, d2))

      val merged1 = g1 merge g2
      merged1.members must be(SortedSet(a1, b2, c1, d2))
      merged1.members.toSeq.map(_.status) must be(Seq(Up, Removed, Leaving, Removed))

      val merged2 = g2 merge g1
      merged2.members must be(SortedSet(a1, b2, c1, d2))
      merged2.members.toSeq.map(_.status) must be(Seq(Up, Removed, Leaving, Removed))

    }

  }
}
