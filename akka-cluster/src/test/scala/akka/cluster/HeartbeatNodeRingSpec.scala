/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import akka.routing.ConsistentHash
import scala.concurrent.duration._
import scala.collection.immutable

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class HeartbeatNodeRingSpec extends WordSpec with MustMatchers {

  val aa = UniqueAddress(Address("akka.tcp", "sys", "aa", 2552), 1)
  val bb = UniqueAddress(Address("akka.tcp", "sys", "bb", 2552), 2)
  val cc = UniqueAddress(Address("akka.tcp", "sys", "cc", 2552), 3)
  val dd = UniqueAddress(Address("akka.tcp", "sys", "dd", 2552), 4)
  val ee = UniqueAddress(Address("akka.tcp", "sys", "ee", 2552), 5)

  val nodes = Set(aa, bb, cc, dd, ee)

  "A HashedNodeRing" must {

    "pick specified number of nodes as receivers" in {
      val ring = HeartbeatNodeRing(cc, nodes, 3)
      ring.myReceivers must be(ring.receivers(cc))

      nodes foreach { n ⇒
        val receivers = ring.receivers(n)
        receivers.size must be(3)
        receivers must not contain (n)
      }
    }

    "pick all except own as receivers when less than total number of nodes" in {
      val expected = Set(aa, bb, dd, ee)
      HeartbeatNodeRing(cc, nodes, 4).myReceivers must be(expected)
      HeartbeatNodeRing(cc, nodes, 5).myReceivers must be(expected)
      HeartbeatNodeRing(cc, nodes, 6).myReceivers must be(expected)
    }

    "pick none when alone" in {
      val ring = HeartbeatNodeRing(cc, Set(cc), 3)
      ring.myReceivers must be(Set())
    }

  }
}
