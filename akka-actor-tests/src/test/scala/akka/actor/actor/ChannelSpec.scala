/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.dispatch._

class ChannelSpec extends WordSpec with MustMatchers {
  "A Channel" must {
    "be contravariant" in {
      val ap = new ActorPromise(1000)
      val p: Promise[Any] = ap
      val c: Channel[Any] = ap
      val cs: Channel[String] = c
    }
  }
}
