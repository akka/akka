/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SwitchSpec extends WordSpec with MustMatchers {

  "Switch" must {

    "on and off" in {
      val s = new Switch(false)
      s.isOff must be(true)
      s.isOn must be(false)

      s.switchOn("hello") must be(true)
      s.isOn must be(true)
      s.isOff must be(false)
      s.switchOn("hello") must be(false)
      s.isOn must be(true)
      s.isOff must be(false)

      s.switchOff("hello") must be(true)
      s.isOff must be(true)
      s.isOn must be(false)
      s.switchOff("hello") must be(false)
      s.isOff must be(true)
      s.isOn must be(false)
    }

    "revert when exception" in {
      val s = new Switch(false)
      intercept[RuntimeException] {
        s.switchOn(throw new RuntimeException)
      }
      s.isOff must be(true)
    }

    "run action without locking" in {
      val s = new Switch(false)
      s.ifOffYield("yes") must be(Some("yes"))
      s.ifOnYield("no") must be(None)
      s.ifOff("yes") must be(true)
      s.ifOn("no") must be(false)

      s.switchOn()
      s.ifOnYield("yes") must be(Some("yes"))
      s.ifOffYield("no") must be(None)
      s.ifOn("yes") must be(true)
      s.ifOff("no") must be(false)
    }

    "run action with locking" in {
      val s = new Switch(false)
      s.whileOffYield("yes") must be(Some("yes"))
      s.whileOnYield("no") must be(None)
      s.whileOff("yes") must be(true)
      s.whileOn("no") must be(false)

      s.switchOn()
      s.whileOnYield("yes") must be(Some("yes"))
      s.whileOffYield("no") must be(None)
      s.whileOn("yes") must be(true)
      s.whileOff("no") must be(false)
    }

    "run first or second action depending on state" in {
      val s = new Switch(false)
      s.fold("on")("off") must be("off")
      s.switchOn()
      s.fold("on")("off") must be("on")
    }

    "do proper locking" in {
      val s = new Switch(false)

      s.locked {
        Thread.sleep(500)
        s.switchOn()
        s.isOn must be(true)
      }

      val latch = new CountDownLatch(1)
      new Thread {
        override def run(): Unit = {
          s.switchOff()
          latch.countDown()
        }
      }.start()

      latch.await(5, TimeUnit.SECONDS)
      s.isOff must be(true)
    }
  }
}
