/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.Done
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }

import scala.util.control.NoStackTrace

class FlowRecoverWithCompleteSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer = ActorMaterializer(settings)

  val ex = new RuntimeException("ex") with NoStackTrace

  "A RecoverWithComplete" must {
    "recover a failure into a completion" in assertAllStagesStopped {
      Source(1 to 4).map { a ⇒ if (a == 3) throw ex else a }
        .recoverWithComplete { case _ ⇒ Done }
        .runWith(TestSink.probe)
        .requestNext(1)
        .requestNext(2)
        .request(1)
        .expectComplete()
    }

    "pass through a normal completion unchanged" in assertAllStagesStopped {
      Source(1 to 2)
        .recoverWithComplete { case _ ⇒ Done }
        .runWith(TestSink.probe)
        .requestNext(1)
        .requestNext(2)
        .request(1)
        .expectComplete()
    }

    "pass through a failure if there is no defined case for that" in assertAllStagesStopped {
      val otherEx = new RuntimeException("otherEx") with NoStackTrace
      Source.failed(ex)
        .recoverWithComplete { case `otherEx` ⇒ Done }
        .runWith(TestSink.probe)
        .request(1)
        .expectError(ex)
    }

  }
}
