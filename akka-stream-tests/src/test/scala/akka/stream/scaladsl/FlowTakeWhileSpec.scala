/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.Utils._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import scala.util.control.NoStackTrace

class FlowTakeWhileSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)

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

    "emit the element that caused the predicate to return false and then no more with inclusive set" in assertAllStagesStopped {
      Source(1 to 10).takeWhile(_ < 3, true).runWith(TestSink.probe[Int])
        .request(4)
        .expectNext(1, 2, 3)
        .expectComplete()
    }

  }

}
