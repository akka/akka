/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.actor.dispatch.ActorModelSpec
import java.util.concurrent.CountDownLatch
import org.junit.{ After, Test }

class CallingThreadDispatcherModelSpec extends ActorModelSpec {
  import ActorModelSpec._
  def newInterceptedDispatcher = new CallingThreadDispatcher with MessageDispatcherInterceptor

  // A CallingThreadDispatcher can by design not process messages in parallel,
  // so disable this test
  //override def dispatcherShouldProcessMessagesInParallel {}

  // This test needs to be adapted: CTD runs the flood completely sequentially
  // with start, invocation, stop, schedule shutdown, abort shutdown, repeat;
  // add "keeper" actor to lock down the dispatcher instance, since the
  // frequent attempted shutdown seems rather costly (random timing failures
  // without this fix)
  override def dispatcherShouldHandleWavesOfActors {
    implicit val dispatcher = newInterceptedDispatcher

    def flood(num: Int) {
      val cachedMessage = CountDownNStop(new CountDownLatch(num))
      val keeper = newTestActor
      (1 to num) foreach { _ ⇒
        newTestActor ! cachedMessage
      }
      keeper.stop()
      assertCountDown(cachedMessage.latch, 10000, "Should process " + num + " countdowns")
    }
    for (run ← 1 to 3) {
      flood(10000)
      assertDispatcher(dispatcher)(starts = run, stops = run)
    }
  }

  /*override def dispatcherShouldCompleteAllUncompletedSenderFuturesOnDeregister {
    //Can't handle this...
  } */

  @After
  def after {
    //remove the interrupted status since we are messing with interrupted exceptions.
    Thread.interrupted()
  }

}

// vim: set ts=2 sw=2 et:
