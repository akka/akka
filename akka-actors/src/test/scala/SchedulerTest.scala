package se.scalablesolutions.akka.actor

import java.util.concurrent.TimeUnit

import org.scalatest.junit.JUnitSuite
import org.junit.Test

class SchedulerTest extends JUnitSuite {
  
  @Test def schedulerShouldSchedule = {
    var count = 0
    case object Tick
    val actor = new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Tick => count += 1
      }}
    actor.start
    Thread.sleep(1000)
    Scheduler.schedule(actor, Tick, 0L, 1L, TimeUnit.SECONDS)
    Thread.sleep(5000)
    Scheduler.shutdown
    assert(count > 0)
  }
}