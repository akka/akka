/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.datareplication

import org.scalatest.WordSpec
import org.scalatest.Matchers

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LWWRegisterSpec extends WordSpec with Matchers {

  "A LWWRegister" must {
    "use latest of successive assignments" in {
      val r = (1 to 100).foldLeft(LWWRegister(0)) {
        case (r, n) ⇒
          r.value should be(n - 1)
          r.withValue(n)
      }
      r.value should be(100)
    }

    "merge by picking max timestamp" in {
      val clock = new LWWRegister.Clock {
        val i = Iterator.from(100)
        def apply() = i.next()
      }
      val r1 = new LWWRegister("A", clock(), clock)
      val r2 = r1.withValue("B")
      val m1 = r1 merge r2
      m1.value should be("B")
      val m2 = r2 merge r1
      m2.value should be("B")
    }

    "use monotonically increasing clock" in {
      val badClock = new LWWRegister.Clock {
        val i = Iterator.from(1)
        def apply() = 100 - i.next()
      }
      val r1 = new LWWRegister("A", badClock(), badClock)
      val r2 = r1.withValue("B")
      val m1 = r1 merge r2
      m1.value should be("B")
      val m2 = r2 merge r1
      m2.value should be("B")
    }
  }
}
