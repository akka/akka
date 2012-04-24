/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dataflow

import akka.actor.{ Actor, Props }
import akka.dispatch.{ Future, Await }
import akka.util.duration._
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import akka.pattern.{ ask, pipe }

class Future2ActorSpec extends AkkaSpec with DefaultTimeout {

  "The Future2Actor bridge" must {

    "support convenient sending to multiple destinations" in {
      Future(42) pipeTo testActor pipeTo testActor
      expectMsgAllOf(1 second, 42, 42)
    }

    "support convenient sending to multiple destinations with implicit sender" in {
      implicit val someActor = system.actorOf(Props(ctx ⇒ Actor.emptyBehavior))
      Future(42) pipeTo testActor pipeTo testActor
      expectMsgAllOf(1 second, 42, 42)
      lastSender must be(someActor)
    }

    "support convenient sending with explicit sender" in {
      val someActor = system.actorOf(Props(ctx ⇒ Actor.emptyBehavior))
      Future(42).to(testActor, someActor)
      expectMsgAllOf(1 second, 42)
      lastSender must be(someActor)
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
