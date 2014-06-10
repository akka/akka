/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.datareplication

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.cluster.UniqueAddress
import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LWWRegisterSpec extends WordSpec with Matchers {

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)

  "A LWWRegister" must {
    "use latest of successive assignments" in {
      val r = (1 to 100).foldLeft(LWWRegister(node1, 0)) {
        case (r, n) ⇒
          r.value should be(n - 1)
          r.withValue(node1, n)
      }
      r.value should be(100)
    }

    "merge by picking max timestamp" in {
      val clock = new LWWRegister.Clock {
        val i = Iterator.from(100)
        def apply() = i.next()
      }
      val r1 = new LWWRegister(node1, "A", clock(), clock)
      val r2 = r1.withValue(node2, "B")
      val m1 = r1 merge r2
      m1.value should be("B")
      val m2 = r2 merge r1
      m2.value should be("B")
    }

    "merge by picking least address when same timestamp" in {
      val clock = new LWWRegister.Clock {
        def apply() = 100
      }
      val r1 = new LWWRegister(node1, "A", clock(), clock)
      val r2 = new LWWRegister(node2, "B", clock(), clock)
      val m1 = r1 merge r2
      m1.value should be("A")
      val m2 = r2 merge r1
      m2.value should be("A")
    }

    "use monotonically increasing clock" in {
      val badClock = new LWWRegister.Clock {
        val i = Iterator.from(1)
        def apply() = 100 - i.next()
      }
      val r1 = new LWWRegister(node1, "A", badClock(), badClock)
      val r2 = r1.withValue(node1, "B")
      val m1 = r1 merge r2
      m1.value should be("B")
      val m2 = r2 merge r1
      m2.value should be("B")
    }
  }
}
