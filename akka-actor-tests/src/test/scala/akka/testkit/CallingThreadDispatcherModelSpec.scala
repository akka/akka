package akka.testkit

import akka.actor.dispatch.ActorModelSpec
import java.util.concurrent.CountDownLatch

class CallingThreadDispatcherModelSpec extends ActorModelSpec {
  import ActorModelSpec._
  def newInterceptedDispatcher = new CallingThreadDispatcher with MessageDispatcherInterceptor

  override def dispatcherShouldProcessMessagesInParallel {}

  override def dispatcherShouldHandleWavesOfActors {
    implicit val dispatcher = newInterceptedDispatcher

    def flood(num: Int) {
      val cachedMessage = CountDownNStop(new CountDownLatch(num))
      val keeper = newTestActor.start()
      (1 to num) foreach {
        _ => newTestActor.start() ! cachedMessage
      }
      keeper.stop
      assertCountDown(cachedMessage.latch,10000, "Should process " + num + " countdowns")
    }
    for(run <- 1 to 3) {
      flood(10000)
      await(dispatcher.stops.get == run)(withinMs = 10000)
      assertDispatcher(dispatcher)(starts = run, stops = run)
    }
  }
}

// vim: set ts=4 sw=4 et:
