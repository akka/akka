/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.scaladsl.adapter._
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
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

      // ordering of these msgs is not guaranteed
      val expectedStarted = (0 to 3).map { n ⇒ s"started $n" }.toSet
      probe.receiveMessages(4).toSet should ===(expectedStarted)

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

  "The router group" must {

    val receptionistDelayMs = 250

    "route messages across routees registered to the receptionist" in {
      val serviceKey = ServiceKey[String]("group-routing-1")
      val probe = createTestProbe[String]()
      val routeeBehavior: Behavior[String] = Behaviors.receiveMessage { msg ⇒
        probe.ref ! msg
        Behaviors.same
      }

      (0 to 3).foreach { n ⇒
        val ref = spawn(routeeBehavior, s"group-1-routee-$n")
        system.receptionist ! Receptionist.register(serviceKey, ref)
      }

      val group = spawn(Routers.group(serviceKey), "group-router-1")

      // give the group a little time to get a listing from the receptionist
      Thread.sleep(receptionistDelayMs)

      (0 to 3).foreach { n ⇒
        val msg = s"message-$n"
        group ! msg
        probe.expectMessage(msg)
      }

      testKit.stop(group)
    }

    "pass messages to dead letters when there are no routees available" in {
      val serviceKey = ServiceKey[String]("group-routing-2")
      val group = spawn(Routers.group(serviceKey), "group-router-2")

      (0 to 3).foreach { n ⇒
        val msg = s"message-$n"
        EventFilter.warning(s"received dead letter without sender: $msg", occurrences = 1).intercept {
          // FIXME why are there two log entries per dead letter?
          EventFilter.info(start = "Message [java.lang.String] without sender to Actor[akka://RouterSpec/deadLetters] was not delivered.", occurrences = 1).intercept {
            group ! msg
          }
        }
      }

      testKit.stop(group)
    }

    "handle a changing set of routees" in {
      val serviceKey = ServiceKey[String]("group-routing-3")
      val probe = createTestProbe[String]()
      val routeeBehavior: Behavior[String] = Behaviors.receiveMessage {
        case "stop" ⇒
          Behaviors.stopped
        case msg ⇒
          probe.ref ! msg
          Behaviors.same
      }

      val ref1 = spawn(routeeBehavior, s"group-3-routee-1")
      system.receptionist ! Receptionist.register(serviceKey, ref1)

      val ref2 = spawn(routeeBehavior, s"group-3-routee-2")
      system.receptionist ! Receptionist.register(serviceKey, ref2)

      val ref3 = spawn(routeeBehavior, s"group-3-routee-3")
      system.receptionist ! Receptionist.register(serviceKey, ref3)

      val group = spawn(Routers.group(serviceKey), "group-router-3")

      // give the group a little time to get a listing from the receptionist
      Thread.sleep(receptionistDelayMs)

      (0 to 3).foreach { n ⇒
        val msg = s"message-$n"
        group ! msg
        probe.expectMessage(msg)
      }

      ref2 ! "stop"

      // give the group a little time to get an updated listing from the receptionist
      Thread.sleep(receptionistDelayMs)

      (0 to 3).foreach { n ⇒
        val msg = s"message-$n"
        group ! msg
        probe.expectMessage(msg)
      }

      testKit.stop(group)

    }

  }

}
