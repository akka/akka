/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dataflow

import akka.actor.{ Actor, Props }
import akka.dispatch.{ Future, Await }
import akka.actor.future2actor
import akka.util.duration._
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout

class Future2ActorSpec extends AkkaSpec with DefaultTimeout {

  "The Future2Actor bridge" must {

    "support convenient sending to multiple destinations" in {
      Future(42) pipeTo testActor pipeTo testActor
      expectMsgAllOf(1 second, 42, 42)
    }

    "support reply via sender" in {
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "do" ⇒ Future(31) pipeTo context.sender
          case "ex" ⇒ Future(throw new AssertionError) pipeTo context.sender
        }
      }))
      Await.result(actor ? "do", timeout.duration) must be(31)
      intercept[AssertionError] {
        Await.result(actor ? "ex", timeout.duration)
      }
    }
  }
}
