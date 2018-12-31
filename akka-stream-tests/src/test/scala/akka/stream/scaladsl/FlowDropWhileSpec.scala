/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink

class FlowDropWhileSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)

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
      Source(1 to 4).dropWhile(a ⇒ if (a < 3) true else throw TE("")).withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectComplete()
    }

    "restart with strategy" in assertAllStagesStopped {
      Source(1 to 4).dropWhile {
        case 1 | 3 ⇒ true
        case 4     ⇒ false
        case 2     ⇒ throw TE("")
      }.withAttributes(supervisionStrategy(restartingDecider))
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(4)
        .expectComplete()
    }

  }

}
