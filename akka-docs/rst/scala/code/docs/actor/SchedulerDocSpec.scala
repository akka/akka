/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor

import language.postfixOps

//#imports1
import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.util.duration._

//#imports1

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._

class SchedulerDocSpec extends AkkaSpec(Map("akka.loglevel" -> "INFO")) {
  "schedule a one-off task" in {
    //#schedule-one-off-message
    //Use the system's dispatcher as ExecutionContext
    import system.dispatcher

    //Schedules to send the "foo"-message to the testActor after 50ms
    system.scheduler.scheduleOnce(50 milliseconds, testActor, "foo")
    //#schedule-one-off-message

    expectMsg(1 second, "foo")

    //#schedule-one-off-thunk
    //Schedules a function to be executed (send the current time) to the testActor after 50ms
    system.scheduler.scheduleOnce(50 milliseconds) {
      testActor ! System.currentTimeMillis
    }
    //#schedule-one-off-thunk

  }

  "schedule a recurring task" in {
    //#schedule-recurring
    val Tick = "tick"
    val tickActor = system.actorOf(Props(new Actor {
      def receive = {
        case Tick ⇒ //Do something
      }
    }))
    //Use system's dispatcher as ExecutionContext
    import system.dispatcher

    //This will schedule to send the Tick-message
    //to the tickActor after 0ms repeating every 50ms
    val cancellable =
      system.scheduler.schedule(0 milliseconds,
        50 milliseconds,
        tickActor,
        Tick)

    //This cancels further Ticks to be sent
    cancellable.cancel()
    //#schedule-recurring
    system.stop(tickActor)
  }
}
