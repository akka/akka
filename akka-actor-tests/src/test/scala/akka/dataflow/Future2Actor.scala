 /**
  *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
  */
package akka.dataflow

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor
import akka.actor.Actor.actorOf
import akka.dispatch.Future
import akka.actor.future2actor
import akka.testkit.TestKit
import akka.util.duration._

class Future2ActorSpec extends WordSpec with MustMatchers with TestKit {

  "The Future2Actor bridge" must {

    "support convenient sending to multiple destinations" in {
      Future(42) pipeTo testActor pipeTo testActor
      expectMsgAllOf(1 second, 42, 42)
    }

    "support reply via channel" in {
      val actor = actorOf(new Actor {
          def receive = {
            case "do" => Future(31) pipeTo self.channel
            case "ex" => Future(throw new AssertionError) pipeTo self.channel
          }
        }).start()
      (actor ? "do").as[Int] must be(Some(31))
      intercept[AssertionError] {
        (actor ? "ex").get
      }
    }
  }
}
