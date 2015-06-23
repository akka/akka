/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.Utils._

import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink

import scala.util.control.NoStackTrace

class FlowDropWhileSpec extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system)

  implicit val materializer = ActorFlowMaterializer(settings)

  "A DropWhile" must {

    "drop while predicate is true" in assertAllStagesStopped {
      Source(1 to 4).dropWhile(_ < 3).runWith(TestSink.probe[Int])
        .request(2)
        .expectNext(3, 4)
        .expectComplete()
    }

    "complete the future for an empty stream" in assertAllStagesStopped {
      Source.empty[Int].dropWhile(_ < 2).runWith(TestSink.probe[Int])
        .request(1)
        .expectComplete()
    }

    "continue if error" in assertAllStagesStopped {
      val testException = new Exception("test") with NoStackTrace
      Source(1 to 4).dropWhile(a ⇒ if (a < 3) true else throw testException).withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectComplete()
    }

  }

}
