/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.testkit.Utils.TE

import scala.concurrent.duration._

class LifecycleInterpreterSpec extends InterpreterSpecKit {
  import akka.stream.Supervision._

  "Interpreter" must {

    "call preStart in order on stages" in new TestSetup(Seq(
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-a"),
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-b"),
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-c"))) {
      expectMsg("start-a")
      expectMsg("start-b")
      expectMsg("start-c")
      expectNoMsg(300.millis)
      upstream.onComplete()
    }

    "call postStop in order on stages - when upstream completes" in new TestSetup(Seq(
      PreStartAndPostStopIdentity(onUpstreamCompleted = () ⇒ testActor ! "complete-a", onStop = () ⇒ testActor ! "stop-a"),
      PreStartAndPostStopIdentity(onUpstreamCompleted = () ⇒ testActor ! "complete-b", onStop = () ⇒ testActor ! "stop-b"),
      PreStartAndPostStopIdentity(onUpstreamCompleted = () ⇒ testActor ! "complete-c", onStop = () ⇒ testActor ! "stop-c"))) {
      upstream.onComplete()
      expectMsg("complete-a")
      expectMsg("stop-a")
      expectMsg("complete-b")
      expectMsg("stop-b")
      expectMsg("complete-c")
      expectMsg("stop-c")
      expectNoMsg(300.millis)
    }

    "call postStop in order on stages - when upstream onErrors" in new TestSetup(Seq(
      PreStartAndPostStopIdentity(
        onUpstreamFailed = ex ⇒ testActor ! ex.getMessage,
        onStop = () ⇒ testActor ! "stop-c"))) {
      val msg = "Boom! Boom! Boom!"
      upstream.onError(TE(msg))
      expectMsg(msg)
      expectMsg("stop-c")
      expectNoMsg(300.millis)
    }

    "call postStop in order on stages - when downstream cancels" in new TestSetup(Seq(
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-a"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-b"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-c"))) {
      downstream.cancel()
      expectMsg("stop-c")
      expectMsg("stop-b")
      expectMsg("stop-a")
      expectNoMsg(300.millis)
    }

    "call preStart before postStop" in new TestSetup(Seq(
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-a", onStop = () ⇒ testActor ! "stop-a"))) {
      expectMsg("start-a")
      expectNoMsg(300.millis)
      upstream.onComplete()
      expectMsg("stop-a")
      expectNoMsg(300.millis)
    }

    "onError when preStart fails" in new TestSetup(Seq(
      PreStartFailer(() ⇒ throw TE("Boom!")))) {
      lastEvents() should ===(Set(Cancel, OnError(TE("Boom!"))))
    }

    "not blow up when postStop fails" in new TestSetup(Seq(
      PostStopFailer(() ⇒ throw TE("Boom!")))) {
      upstream.onComplete()
      lastEvents() should ===(Set(OnComplete))
    }

    "onError when preStart fails with stages after" in new TestSetup(Seq(
      Map((x: Int) ⇒ x, stoppingDecider),
      PreStartFailer(() ⇒ throw TE("Boom!")),
      Map((x: Int) ⇒ x, stoppingDecider))) {
      lastEvents() should ===(Set(Cancel, OnError(TE("Boom!"))))
    }

    "continue with stream shutdown when postStop fails" in new TestSetup(Seq(
      PostStopFailer(() ⇒ throw TE("Boom!")))) {
      lastEvents() should ===(Set())

      upstream.onComplete()
      lastEvents should ===(Set(OnComplete))
    }

    "postStop when pushAndFinish called if upstream completes with pushAndFinish" in new TestSetup(Seq(
      new PushFinishStage(onPostStop = () ⇒ testActor ! "stop"))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))
      expectMsg("stop")
    }

    "postStop when pushAndFinish called with pushAndFinish if indirect upstream completes with pushAndFinish" in new TestSetup(Seq(
      Map((x: Any) ⇒ x, stoppingDecider),
      new PushFinishStage(onPostStop = () ⇒ testActor ! "stop"),
      Map((x: Any) ⇒ x, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))
      expectMsg("stop")
    }

    "postStop when pushAndFinish called with pushAndFinish if upstream completes with pushAndFinish and downstream immediately pulls" in new TestSetup(Seq(
      new PushFinishStage(onPostStop = () ⇒ testActor ! "stop"),
      Fold("", (x: String, y: String) ⇒ x + y, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))
      expectMsg("stop")
    }

  }

}
