/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.datareplication

import org.scalatest.WordSpec
import org.scalatest.Matchers

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlagSpec extends WordSpec with Matchers {

  "A Flag" must {

    "be able to switch on once" in {
      val f1 = Flag()
      val f2 = f1.switchOn
      val f3 = f2.switchOn
      f1.value should be(false)
      f2.value should be(true)
      f3.value should be(true)
    }

    "merge by picking true" in {
      val f1 = Flag()
      val f2 = f1.switchOn
      val m1 = f1 merge f2
      m1.value should be(true)
      val m2 = f2 merge f1
      m2.value should be(true)
    }
  }
}
