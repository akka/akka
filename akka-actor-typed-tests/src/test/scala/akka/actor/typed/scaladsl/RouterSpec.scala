/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.scaladsl.adapter._
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.testkit.EventFilter
import org.scalatest.WordSpecLike

class RouterSpec extends ScalaTestWithActorTestKit("""
    akka.loggers = ["akka.testkit.TestEventListener"]
  """) with WordSpecLike {

  // needed for the event filter
  implicit val untypedSystem = system.toUntyped

  "The router pool" must {

    "create n children and route messages to" in {
      val childCounter = new AtomicInteger(0)
      val probe = createTestProbe[String]()
      val pool = spawn(Routers.pool[String](4)(Behaviors.setup { _ ⇒
        val id = childCounter.getAndIncrement()
        probe.ref ! s"started $id"
        Behaviors.receiveMessage { msg ⇒
          probe.ref ! s"$id $msg"
          Behaviors.same
        }
      }))

      (0 to 3).foreach { n ⇒
        probe.expectMessage(s"started $n")
      }

      (0 to 8).foreach { n ⇒
        pool ! s"message-$n"
        val expectedRecipient = n % 4
        probe.expectMessage(s"$expectedRecipient message-$n")
      }
    }

    "keeps routing to the rest of the children if one child stops" in {
      val probe = createTestProbe[String]()
      val pool = spawn(Routers.pool[String](4)(Behaviors.setup { _ ⇒
        Behaviors.receiveMessage {
          case "stop" ⇒
            Behaviors.stopped
          case msg ⇒
            probe.ref ! msg
            Behaviors.same
        }
      }))

      EventFilter.warning(start = "Pool child stopped", occurrences = 2).intercept {
        pool ! "stop"
        pool ! "stop"
      }

      (0 to 4).foreach { n ⇒
        val msg = s"message-$n"
        pool ! msg
        probe.expectMessage(msg)
      }
    }

    "stops if all children stops" in {
      val probe = createTestProbe()
      val pool = spawn(Routers.pool[String](4)(Behaviors.setup { _ ⇒
        Behaviors.receiveMessage { _ ⇒
          Behaviors.stopped
        }
      }))

      EventFilter.warning(start = "Pool child stopped", occurrences = 4).intercept {
        (0 to 3).foreach { _ ⇒
          pool ! "stop"
        }
      }
      probe.expectTerminated(pool)
    }

  }

}
