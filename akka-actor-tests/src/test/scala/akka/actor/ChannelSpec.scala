/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.dispatch._
import akka.testkit.TestActorRef

class ChannelSpec extends WordSpec with MustMatchers {

  "A Channel" must {

    "be contravariant" in {
      val ap = new ActorPromise(1000)
      val p: Promise[Any] = ap
      val c: Channel[Any] = ap
      val cs: Channel[String] = c
    }

    "find implicit sender actors" in {
      var s: (String, UntypedChannel) = null
      val ch = new Channel[String] {
        def !(msg: String)(implicit sender: UntypedChannel) = { s = (msg, sender) }
      }
      val a = TestActorRef(new Actor {
        def receive = {
          case str: String ⇒ ch ! str
        }
      })
      a ! "hallo"
      s must be(("hallo", a))

      {
        implicit val actor = a
        ch tryTell "buh"
      }
      s must be(("buh", a))
      ch.!("world")(a)
      s must be(("world", a))
      ch.tryTell("bippy")(a)
      s must be(("bippy", a))
    }

  }

}
