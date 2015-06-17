/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorOperationAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.Utils._

import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink

import scala.util.control.NoStackTrace

class FlowTakeWhileSpec extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system)

  implicit val materializer = ActorFlowMaterializer(settings)

  "A TakeWhile" must {

    "take while predicate is true" in assertAllStagesStopped {
      Source(1 to 4).takeWhile(_ < 3).runWith(TestSink.probe[Int])
        .request(3)
        .expectNext(1, 2)
        .expectComplete()
    }

    "complete the future for an empty stream" in assertAllStagesStopped {
      Source.empty[Int].takeWhile(_ < 2).runWith(TestSink.probe[Int])
        .request(1)
        .expectComplete()
    }

    "continue if error" in assertAllStagesStopped {
      val testException = new Exception("test") with NoStackTrace

      val p = Source(1 to 4).takeWhile(a ⇒ if (a == 3) throw testException else true).withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(4)
        .expectNext(1, 2, 4)
        .expectComplete()
    }

  }

}
