/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import language.postfixOps

import akka.testkit.AkkaSpec
import scala.concurrent.util.duration._
import akka.testkit.DefaultTimeout

class AskSpec extends AkkaSpec with DefaultTimeout {

  "The “ask” pattern" must {

    "return broken promises on DeadLetters" in {
      val dead = system.actorFor("/system/deadLetters")
      val f = dead.ask(42)(1 second)
      f.isCompleted must be(true)
      f.value.get match {
        case Left(_: AskTimeoutException) ⇒
        case v                            ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    "return broken promises on EmptyLocalActorRefs" in {
      val empty = system.actorFor("unknown")
      val f = empty ? 3.14
      f.isCompleted must be(true)
      f.value.get match {
        case Left(_: AskTimeoutException) ⇒
        case v                            ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

  }

}