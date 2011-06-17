/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._

import Actor._

class HotSwapSpec extends WordSpec with MustMatchers {

  "An Actor" must {

    "be able to hotswap its behavior with HotSwap(..)" in {
      val barrier = TestBarrier(2)
      @volatile
      var _log = ""
      val a = actorOf(new Actor {
        def receive = { case _ ⇒ _log += "default" }
      }).start()
      a ! HotSwap(self ⇒ {
        case _ ⇒
          _log += "swapped"
          barrier.await
      })
      a ! "swapped"
      barrier.await
      _log must be("swapped")
    }

    "be able to hotswap its behavior with become(..)" in {
      val barrier = TestBarrier(2)
      @volatile
      var _log = ""
      val a = actorOf(new Actor {
        def receive = {
          case "init" ⇒
            _log += "init"
            barrier.await
          case "swap" ⇒ become({
            case _ ⇒
              _log += "swapped"
              barrier.await
          })
        }
      }).start()

      a ! "init"
      barrier.await
      _log must be("init")

      barrier.reset
      _log = ""
      a ! "swap"
      a ! "swapped"
      barrier.await
      _log must be("swapped")
    }

    "be able to revert hotswap its behavior with RevertHotSwap(..)" in {
      val barrier = TestBarrier(2)
      @volatile
      var _log = ""
      val a = actorOf(new Actor {
        def receive = {
          case "init" ⇒
            _log += "init"
            barrier.await
        }
      }).start()

      a ! "init"
      barrier.await
      _log must be("init")

      barrier.reset
      _log = ""
      a ! HotSwap(self ⇒ {
        case "swapped" ⇒
          _log += "swapped"
          barrier.await
      })

      a ! "swapped"
      barrier.await
      _log must be("swapped")

      barrier.reset
      _log = ""
      a ! RevertHotSwap

      a ! "init"
      barrier.await
      _log must be("init")

      // try to revert hotswap below the bottom of the stack
      barrier.reset
      _log = ""
      a ! RevertHotSwap

      a ! "init"
      barrier.await
      _log must be("init")
    }

    "be able to revert hotswap its behavior with unbecome" in {
      val barrier = TestBarrier(2)
      @volatile
      var _log = ""
      val a = actorOf(new Actor {
        def receive = {
          case "init" ⇒
            _log += "init"
            barrier.await
          case "swap" ⇒
            become({
              case "swapped" ⇒
                _log += "swapped"
                barrier.await
              case "revert" ⇒
                unbecome()
            })
            barrier.await
        }
      }).start()

      a ! "init"
      barrier.await
      _log must be("init")

      barrier.reset
      _log = ""
      a ! "swap"
      barrier.await

      barrier.reset
      _log = ""
      a ! "swapped"
      barrier.await
      _log must be("swapped")

      barrier.reset
      _log = ""
      a ! "revert"
      a ! "init"
      barrier.await
      _log must be("init")
    }
  }
}
