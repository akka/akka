/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props

object ActorRefSinkSpec {
  case class Fw(ref: ActorRef) extends Actor {
    def receive = {
      case msg ⇒ ref forward msg
    }
  }
}

class ActorRefSinkSpec extends AkkaSpec {
  import ActorRefSinkSpec._
  implicit val mat = ActorFlowMaterializer()

  "A ActorRefSink" must {

    "send the elements to the ActorRef" in {
      Source(List(1, 2, 3)).runWith(Sink.actorRef(testActor, onCompleteMessage = "done"))
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg("done")
    }

    "cancel stream when actor terminates" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val fw = system.actorOf(Props(classOf[Fw], testActor).withDispatcher("akka.test.stream-dispatcher"))
      Source(publisher).runWith(Sink.actorRef(fw, onCompleteMessage = "done"))
      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      autoPublisher.sendNext(1)
      autoPublisher.sendNext(2)
      expectMsg(1)
      expectMsg(2)
      system.stop(fw)
      autoPublisher.subscription.expectCancellation()
    }

  }

}
