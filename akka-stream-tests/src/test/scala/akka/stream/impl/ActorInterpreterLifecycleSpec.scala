/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.stream.Supervision._
import akka.stream._
import akka.stream.impl.fusing.{ InterpreterLifecycleSpecKit, ActorInterpreter }
import akka.stream.stage.Stage
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.{ AkkaSpec, _ }
import scala.concurrent.duration._

class ActorInterpreterLifecycleSpec extends AkkaSpec with InterpreterLifecycleSpecKit {

  implicit val mat = ActorFlowMaterializer()

  class Setup(ops: List[Stage[_, _]] = List(fusing.Map({ x: Any ⇒ x }, stoppingDecider))) {
    val up = TestPublisher.manualProbe[Int]
    val down = TestSubscriber.manualProbe[Int]
    private val props = ActorInterpreter.props(mat.settings, ops, mat).withDispatcher("akka.test.stream-dispatcher")
    val actor = system.actorOf(props)
    val processor = ActorProcessorFactory[Int, Int](actor)
  }

  "An ActorInterpreter" must {

    "call preStart in order on stages" in new Setup(List(
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-a"),
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-b"),
      PreStartAndPostStopIdentity(onStart = _ ⇒ testActor ! "start-c"))) {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      sub.cancel()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()

      expectMsg("start-a")
      expectMsg("start-b")
      expectMsg("start-c")
    }

    "call postStart in order on stages - when upstream completes" in new Setup(List(
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-a"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-b"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-c"))) {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      upsub.sendComplete()
      down.expectComplete()

      expectMsg("stop-a")
      expectMsg("stop-b")
      expectMsg("stop-c")
    }

    "call postStart in order on stages - when downstream cancels" in new Setup(List(
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-a"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-b"),
      PreStartAndPostStopIdentity(onStop = () ⇒ testActor ! "stop-c"))) {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      sub.cancel()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()

      expectMsg("stop-c")
      expectMsg("stop-b")
      expectMsg("stop-a")
    }

    "onError downstream when preStart fails" in new Setup(List(
      PreStartFailer(() ⇒ throw TE("Boom!")))) {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      down.expectError(TE("Boom!"))
    }

    "onError only once even with Supervision.restart" in new Setup(List(
      PreStartFailer(() ⇒ throw TE("Boom!")))) {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      down.expectError(TE("Boom!"))
      down.expectNoMsg(1.second)
    }

    "onError downstream when preStart fails with 'most downstream' failure, when multiple stages fail" in new Setup(List(
      PreStartFailer(() ⇒ throw TE("Boom 1!")),
      PreStartFailer(() ⇒ throw TE("Boom 2!")),
      PreStartFailer(() ⇒ throw TE("Boom 3!")))) {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      down.expectError(TE("Boom 3!"))
      down.expectNoMsg(300.millis)
    }

    "continue with stream shutdown when postStop fails" in new Setup(List(
      PostStopFailer(() ⇒ throw TE("Boom!")))) {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      upsub.sendComplete()
      down.expectComplete() // failures in postStop are logged, but not propagated // TODO Future features? make this a setting?
    }

  }

}
