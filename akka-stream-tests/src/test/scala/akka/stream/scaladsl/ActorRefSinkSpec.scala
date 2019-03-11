/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props

object ActorRefSinkSpec {
  case class Fw(ref: ActorRef) extends Actor {
    def receive = {
      case msg => ref.forward(msg)
    }
  }
}

class ActorRefSinkSpec extends StreamSpec {
  import ActorRefSinkSpec._
  implicit val materializer = ActorMaterializer()

  "A ActorRefSink" must {

    "send the elements to the ActorRef" in assertAllStagesStopped {
      Source(List(1, 2, 3)).runWith(Sink.actorRef(testActor, onCompleteMessage = "done"))
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg("done")
    }

    "cancel stream when actor terminates" in assertAllStagesStopped {
      val fw = system.actorOf(Props(classOf[Fw], testActor).withDispatcher("akka.test.stream-dispatcher"))
      val publisher =
        TestSource.probe[Int].to(Sink.actorRef(fw, onCompleteMessage = "done")).run().sendNext(1).sendNext(2)
      expectMsg(1)
      expectMsg(2)
      system.stop(fw)
      publisher.expectCancellation()
    }

  }

}
