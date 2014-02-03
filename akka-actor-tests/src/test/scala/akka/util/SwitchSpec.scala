/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.scalatest.WordSpec
import org.scalatest.Matchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SwitchSpec extends WordSpec with Matchers {

  "Switch" must {

    "on and off" in {
      val s = new Switch(false)
      s.isOff should be(true)
      s.isOn should be(false)

      s.switchOn(()) should be(true)
      s.isOn should be(true)
      s.isOff should be(false)
      s.switchOn(()) should be(false)
      s.isOn should be(true)
      s.isOff should be(false)

      s.switchOff(()) should be(true)
      s.isOff should be(true)
      s.isOn should be(false)
      s.switchOff(()) should be(false)
      s.isOff should be(true)
      s.isOn should be(false)
    }

    "revert when exception" in {
      val s = new Switch(false)
      intercept[RuntimeException] {
        s.switchOn(throw new RuntimeException)
      }
      s.isOff should be(true)
    }

    "run action without locking" in {
      val s = new Switch(false)
      s.ifOffYield("yes") should be(Some("yes"))
      s.ifOnYield("no") should be(None)
      s.ifOff(()) should be(true)
      s.ifOn(()) should be(false)

      s.switchOn(())
      s.ifOnYield("yes") should be(Some("yes"))
      s.ifOffYield("no") should be(None)
      s.ifOn(()) should be(true)
      s.ifOff(()) should be(false)
    }

    "run action with locking" in {
      val s = new Switch(false)
      s.whileOffYield("yes") should be(Some("yes"))
      s.whileOnYield("no") should be(None)
      s.whileOff(()) should be(true)
      s.whileOn(()) should be(false)

      s.switchOn(())
      s.whileOnYield("yes") should be(Some("yes"))
      s.whileOffYield("no") should be(None)
      s.whileOn(()) should be(true)
      s.whileOff(()) should be(false)
    }

    "run first or second action depending on state" in {
      val s = new Switch(false)
      s.fold("on")("off") should be("off")
      s.switchOn(())
      s.fold("on")("off") should be("on")
    }

    "do proper locking" in {
      val s = new Switch(false)

      s.locked {
        Thread.sleep(500)
        s.switchOn(())
        s.isOn should be(true)
      }

      val latch = new CountDownLatch(1)
      new Thread {
        override def run(): Unit = {
          s.switchOff(())
          latch.countDown()
        }
      }.start()

      latch.await(5, TimeUnit.SECONDS)
      s.isOff should be(true)
    }
  }
}
