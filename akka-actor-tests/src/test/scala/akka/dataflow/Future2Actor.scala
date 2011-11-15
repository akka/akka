/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dataflow

import akka.actor.{ Actor, Props }
import akka.dispatch.Future
import akka.actor.future2actor
import akka.util.duration._
import akka.testkit.AkkaSpec

class Future2ActorSpec extends AkkaSpec {

  "The Future2Actor bridge" must {

    "support convenient sending to multiple destinations" in {
      Future(42) pipeTo testActor pipeTo testActor
      expectMsgAllOf(1 second, 42, 42)
    }

    "support reply via sender" in {
      val actor = app.actorOf(Props(new Actor {
        def receive = {
          case "do" ⇒ Future(31) pipeTo context.sender
          case "ex" ⇒ Future(throw new AssertionError) pipeTo context.sender
        }
      }))
      (actor ? "do").as[Int] must be(Some(31))
      intercept[AssertionError] {
        (actor ? "ex").get
      }
    }
  }
}
