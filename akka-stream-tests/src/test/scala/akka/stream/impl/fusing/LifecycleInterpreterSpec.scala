/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.stream.stage._
import akka.testkit.AkkaSpec
import akka.stream.testkit.Utils.TE

import scala.concurrent.duration._

class LifecycleInterpreterSpec extends AkkaSpec with GraphInterpreterSpecKit {
  import akka.stream.Supervision._

  "Interpreter" must {

    "call preStart in order on stages" in new OneBoundedSetup[String](Seq(
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-a"),
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-b"),
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-c"))) {
      expectMsg("start-a")
      expectMsg("start-b")
      expectMsg("start-c")
      expectNoMsg(300.millis)
      upstream.onComplete()
    }

    "call postStop in order on stages - when upstream completes" in new OneBoundedSetup[String](Seq(
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

    "call postStop in order on stages - when upstream onErrors" in new OneBoundedSetup[String](Seq(
      PreStartAndPostStopIdentity(
        onUpstreamFailed = ex ⇒ testActor ! ex.getMessage,
        onStop = () ⇒ testActor ! "stop-c"))) {
      val msg = "Boom! Boom! Boom!"
      upstream.onError(TE(msg))
      expectMsg(msg)
      expectMsg("stop-c")
      expectNoMsg(300.millis)
    }

    "call postStop in order on stages - when downstream cancels" in new OneBoundedSetup[String](Seq(
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-a"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-b"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-c"))) {
      downstream.cancel()
      expectMsg("stop-c")
      expectMsg("stop-b")
      expectMsg("stop-a")
      expectNoMsg(300.millis)
    }

    "call preStart before postStop" in new OneBoundedSetup[String](Seq(
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-a", onStop = () ⇒ testActor ! "stop-a"))) {
      expectMsg("start-a")
      expectNoMsg(300.millis)
      upstream.onComplete()
      expectMsg("stop-a")
      expectNoMsg(300.millis)
    }

    "onError when preStart fails" in new OneBoundedSetup[String](Seq(
      PreStartFailer(() ⇒ throw TE("Boom!")))) {
      lastEvents() should ===(Set(Cancel, OnError(TE("Boom!"))))
    }

    "not blow up when postStop fails" in new OneBoundedSetup[String](Seq(
      PostStopFailer(() ⇒ throw TE("Boom!")))) {
      upstream.onComplete()
      lastEvents() should ===(Set(OnComplete))
    }

    "onError when preStart fails with stages after" in new OneBoundedSetup[String](Seq(
      Map((x: Int) ⇒ x, stoppingDecider),
      PreStartFailer(() ⇒ throw TE("Boom!")),
      Map((x: Int) ⇒ x, stoppingDecider))) {
      lastEvents() should ===(Set(Cancel, OnError(TE("Boom!"))))
    }

    "continue with stream shutdown when postStop fails" in new OneBoundedSetup[String](Seq(
      PostStopFailer(() ⇒ throw TE("Boom!")))) {
      lastEvents() should ===(Set())

      upstream.onComplete()
      lastEvents should ===(Set(OnComplete))
    }

    "postStop when pushAndFinish called if upstream completes with pushAndFinish" in new OneBoundedSetup[String](Seq(
      new PushFinishStage(onPostStop = () ⇒ testActor ! "stop"))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))
      expectMsg("stop")
    }

    "postStop when pushAndFinish called with pushAndFinish if indirect upstream completes with pushAndFinish" in new OneBoundedSetup[String](Seq(
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

    "postStop when pushAndFinish called with pushAndFinish if upstream completes with pushAndFinish and downstream immediately pulls" in new OneBoundedSetup[String](Seq(
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

  private[akka] case class PreStartAndPostStopIdentity[T](
    onStart: LifecycleContext ⇒ Unit = _ ⇒ (),
    onStop: () ⇒ Unit = () ⇒ (),
    onUpstreamCompleted: () ⇒ Unit = () ⇒ (),
    onUpstreamFailed: Throwable ⇒ Unit = ex ⇒ ())
    extends PushStage[T, T] {
    override def preStart(ctx: LifecycleContext) = onStart(ctx)

    override def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)

    override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
      onUpstreamCompleted()
      super.onUpstreamFinish(ctx)
    }

    override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
      onUpstreamFailed(cause)
      super.onUpstreamFailure(cause, ctx)
    }

    override def postStop() = onStop()
  }

  private[akka] case class PreStartFailer[T](pleaseThrow: () ⇒ Unit) extends PushStage[T, T] {

    override def preStart(ctx: LifecycleContext) =
      pleaseThrow()

    override def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)
  }

  private[akka] case class PostStopFailer[T](ex: () ⇒ Throwable) extends PushStage[T, T] {
    override def onUpstreamFinish(ctx: Context[T]) = ctx.finish()
    override def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)

    override def postStop(): Unit = throw ex()
  }

  // This test is related to issue #17351
  private[akka] class PushFinishStage(onPostStop: () ⇒ Unit = () ⇒ ()) extends PushStage[Any, Any] {
    override def onPush(elem: Any, ctx: Context[Any]): SyncDirective =
      ctx.pushAndFinish(elem)

    override def onUpstreamFinish(ctx: Context[Any]): TerminationDirective =
      ctx.fail(akka.stream.testkit.Utils.TE("Cannot happen"))

    override def postStop(): Unit =
      onPostStop()
  }

}
