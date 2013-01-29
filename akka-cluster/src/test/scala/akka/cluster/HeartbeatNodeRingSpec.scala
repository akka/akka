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

  val aa = Address("akka", "sys", "aa", 2552)
  val bb = Address("akka", "sys", "bb", 2552)
  val cc = Address("akka", "sys", "cc", 2552)
  val dd = Address("akka", "sys", "dd", 2552)
  val ee = Address("akka", "sys", "ee", 2552)

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

    "have matching senders and receivers" in {
      val ring = HeartbeatNodeRing(cc, nodes, 3)
      ring.mySenders must be(ring.senders(cc))

      for (sender ← nodes; receiver ← ring.receivers(sender)) {
        ring.senders(receiver) must contain(sender)
      }
    }

    "pick none when alone" in {
      val ring = HeartbeatNodeRing(cc, Set(cc), 3)
      ring.myReceivers must be(Set())
      ring.mySenders must be(Set())
    }

  }
}
