package akka.contrib.throttle

import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit }
import akka.util.Timeout
import org.scalatest.{ FunSuiteLike, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration._

class StopWatchTest extends TestKit(ActorSystem("stopwatch-test")) with FunSuiteLike with Matchers with ImplicitSender {
  test("ask stopwatch for ticks") {
    val stopWatchActor = TestActorRef[StopWatch]
    stopWatchActor ! Click
    stopWatchActor ! Click

    import akka.pattern.ask
    implicit val timeout = Timeout(61.seconds)
    val ticks = Await.result((stopWatchActor ? Ticks).mapTo[Int], 60.seconds)
    ticks should be(2)

  }

  test("Report current averageRate when sent 5 messages per.second at a uniform rate") {
    val stopWatchActor = TestActorRef[StopWatch]
    import system.dispatcher
    val scheduledStopwatchClicker = system.scheduler.schedule(200.millis, 200.millis) {
      stopWatchActor ! Click
    }
    val scheduledChecker = system.scheduler.scheduleOnce(1100.millis) {
      stopWatchActor ! MaxRate()
    }

    val maxAverageRate: MaxRateResponse = expectMsgPF(2.seconds) { case r: MaxRateResponse ⇒ r }
    scheduledStopwatchClicker.cancel()
    maxAverageRate.rate should be(5)

  }

  test("Report current averageRate when sent 10 messages per.second at a uniform rate") {
    val stopWatchActor = TestActorRef[StopWatch]
    import system.dispatcher
    val scheduledStopwatchClicker = system.scheduler.schedule(100.millis, 100.millis) { stopWatchActor ! Click }
    val scheduledChecker = system.scheduler.scheduleOnce(1009.millis) {
      stopWatchActor ! MaxRate()
    }

    val maxAverageRate: MaxRateResponse = expectMsgPF(2.seconds) { case r: MaxRateResponse ⇒ r }
    scheduledStopwatchClicker.cancel()
    maxAverageRate.rate should be(10)

  }

  test("Report current averageRate when sent 5 messages per.second at a non-uniform rate") {
    val stopWatchActor = TestActorRef[StopWatch]
    import system.dispatcher
    system.scheduler.scheduleOnce(230.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(250.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1.second) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1200.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1210.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1300.millis) { stopWatchActor ! MaxRate() }

    val maxAverageRate: MaxRateResponse = expectMsgPF(1500.millis) { case r: MaxRateResponse ⇒ r }
    maxAverageRate.rate should be(5)

  }

  test("Report current averageRate when sent 10 messages per.second at a non-uniform rate") {
    val stopWatchActor = TestActorRef[StopWatch]
    import system.dispatcher
    system.scheduler.scheduleOnce(230.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(250.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(260.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(270.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1.second) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1200.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1205.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1208.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1210.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1220.millis) { stopWatchActor ! Click }

    system.scheduler.scheduleOnce(1350.millis) { stopWatchActor ! MaxRate() }

    val maxAverageRate: MaxRateResponse = expectMsgPF(1500.millis) { case r: MaxRateResponse ⇒ r }
    maxAverageRate.rate should be(10)

  }

  test("Faithfully report count in last period") {
    val stopWatchActor = TestActorRef[StopWatch]
    import system.dispatcher
    system.scheduler.scheduleOnce(230.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(250.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(260.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(270.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1.second) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1200.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1205.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1208.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1210.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1220.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(2.seconds) { stopWatchActor ! Click }

    system.scheduler.scheduleOnce(1300.millis) { stopWatchActor ! LastPeriodCount(1.second) }
    system.scheduler.scheduleOnce(2300.millis) { stopWatchActor ! LastPeriodCount(1.second) }

    val lastPeriodCountResponse: LastPeriodCountResponse = expectMsgPF(1500.millis) { case r: LastPeriodCountResponse ⇒ r }
    lastPeriodCountResponse.count should be(6)

    val lastPeriodCountResponse2: LastPeriodCountResponse = expectMsgPF(2800.millis) { case r: LastPeriodCountResponse ⇒ r }
    lastPeriodCountResponse2.count should be(1)

  }

  test("Purge all but last period") {
    val stopWatchActor = TestActorRef[StopWatch]
    import system.dispatcher
    system.scheduler.scheduleOnce(230.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(250.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(260.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(270.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1.second) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1200.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1205.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1208.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1210.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1220.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1300.millis) { stopWatchActor ! Click }
    system.scheduler.scheduleOnce(1400.millis) { stopWatchActor ! Click }

    system.scheduler.scheduleOnce(1900.millis) {
      stopWatchActor ! Purge(1.second)
      stopWatchActor ! Ticks
    }

    val ticks = expectMsgPF(2200.millis) { case r: Int ⇒ r }
    ticks should be(8)

  }

}
