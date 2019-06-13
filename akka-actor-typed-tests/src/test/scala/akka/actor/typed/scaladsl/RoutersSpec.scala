/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.DeadLetter
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.EventFilter
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

class RoutersSpec extends ScalaTestWithActorTestKit("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel=debug
  """) with WordSpecLike with Matchers {

  // needed for the event filter
  implicit val untypedSystem = system.toUntyped

  def compileOnlyApiCoverage(): Unit = {
    Routers.group(ServiceKey[String]("key")).withRandomRouting().withRoundRobinRouting()

    Routers.pool(10)(() => Behaviors.empty[Any]).withRandomRouting()
    Routers.pool(10)(() => Behaviors.empty[Any]).withRoundRobinRouting()
  }

  "The router pool" must {

    "create n children and route messages to" in {
      val childCounter = new AtomicInteger(0)
      case class Ack(msg: String, recipient: Int)
      val probe = createTestProbe[AnyRef]()
      val pool = spawn(Routers.pool[String](4)(() =>
        Behaviors.setup { _ =>
          val id = childCounter.getAndIncrement()
          probe.ref ! s"started $id"
          Behaviors.receiveMessage { msg =>
            probe.ref ! Ack(msg, id)
            Behaviors.same
          }
        }))

      // ordering of these msgs is not guaranteed
      val expectedStarted = (0 to 3).map { n =>
        s"started $n"
      }.toSet
      probe.receiveMessages(4).toSet should ===(expectedStarted)

      // send one message at a time and see we rotate over all children, note that we don't necessarily
      // know what order the logic is rotating over the children, so we just check we reach all of them
      val sent = (0 to 8).map { n =>
        val msg = s"message-$n"
        pool ! msg
        msg
      }

      val acks = (0 to 8).map(_ => probe.expectMessageType[Ack])
      val (recipients, messages) = acks.foldLeft[(Set[Int], Set[String])]((Set.empty, Set.empty)) {
        case ((recipients, messages), ack) =>
          (recipients + ack.recipient, messages + ack.msg)
      }
      recipients should ===(Set(0, 1, 2, 3))
      messages should ===(sent.toSet)
    }

    "keep routing to the rest of the children if some children stops" in {
      val probe = createTestProbe[String]()
      val pool = spawn(Routers.pool[String](4)(() =>
        Behaviors.receiveMessage {
          case "stop" =>
            Behaviors.stopped
          case msg =>
            probe.ref ! msg
            Behaviors.same
        }))

      EventFilter.debug(start = "Pool child stopped", occurrences = 2).intercept {
        pool ! "stop"
        pool ! "stop"
      }

      // there is a race here where the child stopped but the router did not see that message yet, and may
      // deliver messages to it, which will end up in dead letters.
      // this test protects against that by waiting for the log entry to show up

      val responses = (0 to 4).map { n =>
        val msg = s"message-$n"
        pool ! msg
        probe.expectMessageType[String]
      }

      (responses should contain).allOf("message-0", "message-1", "message-2", "message-3", "message-4")
    }

    "stops if all children stops" in {
      val probe = createTestProbe()
      val pool = spawn(Routers.pool[String](4)(() =>
        Behaviors.receiveMessage { _ =>
          Behaviors.stopped
        }))

      EventFilter.info(start = "Last pool child stopped, stopping pool", occurrences = 1).intercept {
        (0 to 3).foreach { _ =>
          pool ! "stop"
        }
        probe.expectTerminated(pool)
      }
    }

  }

  "The router group" must {

    val receptionistDelayMs = 250

    "route messages across routees registered to the receptionist" in {
      val serviceKey = ServiceKey[String]("group-routing-1")
      val probe = createTestProbe[String]()
      val routeeBehavior: Behavior[String] = Behaviors.receiveMessage { msg =>
        probe.ref ! msg
        Behaviors.same
      }

      (0 to 3).foreach { n =>
        val ref = spawn(routeeBehavior, s"group-1-routee-$n")
        system.receptionist ! Receptionist.register(serviceKey, ref)
      }

      val group = spawn(Routers.group(serviceKey), "group-router-1")

      // ok to do right away
      (0 to 3).foreach { n =>
        val msg = s"message-$n"
        group ! msg
        probe.expectMessage(msg)
      }

      testKit.stop(group)
    }

    "pass messages to dead letters when there are no routees available" in {
      val serviceKey = ServiceKey[String]("group-routing-2")
      val group = spawn(Routers.group(serviceKey), "group-router-2")
      val probe = TestProbe[DeadLetter]()
      system.toUntyped.eventStream.subscribe(probe.ref.toUntyped, classOf[DeadLetter])

      (0 to 3).foreach { n =>
        val msg = s"message-$n"
        /* FIXME cant watch log events until #26432 is fixed
         EventFilter.info(start = "Message [java.lang.String] ... was not delivered.", occurrences = 1).intercept { */
        group ! msg
        probe.expectMessageType[DeadLetter]
      /* } */
      }

      testKit.stop(group)
    }

    "handle a changing set of routees" in {
      val serviceKey = ServiceKey[String]("group-routing-3")
      val probe = createTestProbe[String]()
      val routeeBehavior: Behavior[String] = Behaviors.receiveMessage {
        case "stop" =>
          Behaviors.stopped
        case msg =>
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

      (0 to 3).foreach { n =>
        val msg = s"message-$n"
        group ! msg
        probe.expectMessage(msg)
      }

      ref2 ! "stop"

      // give the group a little time to get an updated listing from the receptionist
      Thread.sleep(receptionistDelayMs)

      (0 to 3).foreach { n =>
        val msg = s"message-$n"
        group ! msg
        probe.expectMessage(msg)
      }

      testKit.stop(group)

    }

  }

}
