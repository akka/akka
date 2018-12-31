/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.util.control.NoStackTrace
import akka.stream.ActorMaterializer
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowForeachSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  "A runForeach" must {

    "call the procedure for each element" in assertAllStagesStopped {
      Source(1 to 3).runForeach(testActor ! _) foreach {
        _ ⇒ testActor ! "done"
      }
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg("done")
    }

    "complete the future for an empty stream" in assertAllStagesStopped {
      Source.empty[String].runForeach(testActor ! _) foreach {
        _ ⇒ testActor ! "done"
      }
      expectMsg("done")
    }

    "yield the first error" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).runForeach(testActor ! _).failed foreach {
        ex ⇒ testActor ! ex
      }
      val proc = p.expectSubscription()
      proc.expectRequest()
      val rte = new RuntimeException("ex") with NoStackTrace
      proc.sendError(rte)
      expectMsg(rte)
    }

    "complete future with failure when function throws" in assertAllStagesStopped {
      val error = TE("Boom!")
      val future = Source.single(1).runForeach(_ ⇒ throw error)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

  }

}
