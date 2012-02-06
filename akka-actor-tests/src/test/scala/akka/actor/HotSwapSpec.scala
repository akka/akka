/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class HotSwapSpec extends AkkaSpec with ImplicitSender {

  "An Actor" must {

    "be able to hotswap its behavior with become(..)" in {
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "init" ⇒ sender ! "init"
          case "swap" ⇒ context.become({ case x: String ⇒ context.sender ! x })
        }
      }))

      a ! "init"
      expectMsg("init")
      a ! "swap"
      a ! "swapped"
      expectMsg("swapped")
    }

    "be able to revert hotswap its behavior with unbecome" in {
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "init" ⇒ sender ! "init"
          case "swap" ⇒
            context.become({
              case "swapped" ⇒
                sender ! "swapped"
              case "revert" ⇒
                context.unbecome()
            })
        }
      }))

      a ! "init"
      expectMsg("init")
      a ! "swap"

      a ! "swapped"
      expectMsg("swapped")

      a ! "revert"
      a ! "init"
      expectMsg("init")
    }

    "revert to initial state on restart" in {

      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "state" ⇒ sender ! "0"
          case "swap" ⇒
            context.become({
              case "state"   ⇒ sender ! "1"
              case "swapped" ⇒ sender ! "swapped"
              case "crash"   ⇒ throw new Exception("Crash (expected)!")
            })
            sender ! "swapped"
        }
      }))
      a ! "state"
      expectMsg("0")
      a ! "swap"
      expectMsg("swapped")
      a ! "state"
      expectMsg("1")
      EventFilter[Exception](message = "Crash (expected)!", occurrences = 1) intercept { a ! "crash" }
      a ! "state"
      expectMsg("0")
    }
  }
}
