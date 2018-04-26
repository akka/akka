/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.Done

import scala.util.control.NoStackTrace
import akka.stream.ActorMaterializer
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class FlowWireTapSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  "A wireTap" must {

    "call the procedure for each element" in assertAllStagesStopped {
      Source(1 to 3).wireTap(x ⇒ { testActor ! x }).runWith(Sink.ignore).futureValue
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
    }

    "complete the future for an empty stream" in assertAllStagesStopped {
      Source.empty[String].wireTap(testActor ! _).runWith(Sink.ignore) foreach {
        _ ⇒ testActor ! "done"
      }
      expectMsg("done")
    }

    "yield the first error" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).wireTap(testActor ! _).runWith(Sink.ignore).failed foreach {
        ex ⇒ testActor ! ex
      }
      val proc = p.expectSubscription()
      proc.expectRequest()
      val rte = new RuntimeException("ex") with NoStackTrace
      proc.sendError(rte)
      expectMsg(rte)
    }

    "not cause subsequent stages to be failed if throws (same as wireTap(Sink))" in assertAllStagesStopped {
      val error = TE("Boom!")
      val future = Source.single(1).wireTap(_ ⇒ throw error).runWith(Sink.ignore)
      future.futureValue shouldEqual Done
    }
  }

}
