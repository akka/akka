/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.pattern

import language.postfixOps

import akka.actor.{ Props, ActorRef, Actor }
import scala.concurrent.util.duration._
import scala.concurrent.util.{ FiniteDuration, Duration }
import akka.testkit.{ TimingTest, AkkaSpec, filterException }
import docs.pattern.SchedulerPatternSpec.ScheduleInConstructor

object SchedulerPatternSpec {
  //#schedule-constructor
  class ScheduleInConstructor extends Actor {
    val tick =
      context.system.scheduler.schedule(500 millis, 1000 millis, self, "tick")
    //#schedule-constructor
    // this var and constructor is declared here to not show up in the docs
    var target: ActorRef = null
    def this(target: ActorRef) = { this(); this.target = target }
    //#schedule-constructor

    override def postStop() = tick.cancel()

    def receive = {
      case "tick" ⇒
        // do something useful here
        //#schedule-constructor
        target ! "tick"
      case "restart" ⇒
        throw new ArithmeticException
      //#schedule-constructor
    }
  }
  //#schedule-constructor

  //#schedule-receive
  class ScheduleInReceive extends Actor {
    import context._
    //#schedule-receive
    // this var and constructor is declared here to not show up in the docs
    var target: ActorRef = null
    def this(target: ActorRef) = { this(); this.target = target }
    //#schedule-receive

    override def preStart() =
      system.scheduler.scheduleOnce(500 millis, self, "tick")

    // override postRestart so we don't call preStart and schedule a new message
    override def postRestart(reason: Throwable) = {}

    def receive = {
      case "tick" ⇒
        // send another periodic tick after the specified delay
        system.scheduler.scheduleOnce(1000 millis, self, "tick")
        // do something useful here
        //#schedule-receive
        target ! "tick"
      case "restart" ⇒
        throw new ArithmeticException
      //#schedule-receive
    }
  }
  //#schedule-receive
}

class SchedulerPatternSpec extends AkkaSpec {

  def testSchedule(actor: ActorRef, startDuration: FiniteDuration,
                   afterRestartDuration: FiniteDuration) = {

    filterException[ArithmeticException] {
      within(startDuration) {
        expectMsg("tick")
        expectMsg("tick")
        expectMsg("tick")
      }
      actor ! "restart"
      within(afterRestartDuration) {
        expectMsg("tick")
        expectMsg("tick")
      }
      system.stop(actor)
    }
  }

  "send periodic ticks from the constructor" taggedAs TimingTest in {
    testSchedule(system.actorOf(Props(new ScheduleInConstructor(testActor))),
      3000 millis, 2000 millis)
  }

  "send ticks from the preStart and receive" taggedAs TimingTest in {
    testSchedule(system.actorOf(Props(new ScheduleInConstructor(testActor))),
      3000 millis, 2500 millis)
  }
}
