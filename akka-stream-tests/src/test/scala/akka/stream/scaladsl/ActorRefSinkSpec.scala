/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.actor.{ Actor, ActorRef, Props }
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl._
import akka.testkit.TestProbe

import scala.util.control.NoStackTrace

object ActorRefSinkSpec {
  case class Fw(ref: ActorRef) extends Actor {
    def receive = {
      case msg => ref.forward(msg)
    }
  }

  val te = new RuntimeException("oh dear") with NoStackTrace
}

class ActorRefSinkSpec extends StreamSpec {
  import ActorRefSinkSpec._

  "A ActorRefSink" must {

    "send the elements to the ActorRef" in assertAllStagesStopped {
      Source(List(1, 2, 3)).runWith(Sink.actorRef(testActor, onCompleteMessage = "done", _ => "failure"))
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg("done")
    }

    "cancel stream when actor terminates" in assertAllStagesStopped {
      val fw = system.actorOf(Props(classOf[Fw], testActor).withDispatcher("akka.test.stream-dispatcher"))
      val publisher =
        TestSource
          .probe[Int]
          .to(Sink.actorRef(fw, onCompleteMessage = "done", _ => "failure"))
          .run()
          .sendNext(1)
          .sendNext(2)
      expectMsg(1)
      expectMsg(2)
      system.stop(fw)
      publisher.expectCancellation()
    }

    "sends error message if upstream fails" in assertAllStagesStopped {
      val actorProbe = TestProbe()
      val probe = TestSource.probe[String].to(Sink.actorRef(actorProbe.ref, "complete", _ => "failure")).run()
      probe.sendError(te)
      actorProbe.expectMsg("failure")
    }
  }

}
