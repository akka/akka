/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dungeon

import akka.actor.Actor
import akka.actor.Props

import akka.testkit._

object DispatchSpec {
  class UnserializableMessageClass
  class EmptyActor extends Actor {
    override def receive = {
      case _: UnserializableMessageClass ⇒ // OK
    }
  }
}
class DispatchSpec extends AkkaSpec("akka.actor.serialize-messages = on") with DefaultTimeout {
  import DispatchSpec._

  "The dispatcher" should {
    "log an appropriate message when akka.actor.serialize-messages triggers a serialization error" in {
      val actor = system.actorOf(Props[EmptyActor])
      EventFilter[Exception](pattern = ".*NoSerializationVerificationNeeded.*", occurrences = 1) intercept {
        actor ! new UnserializableMessageClass
      }
    }
  }
}

