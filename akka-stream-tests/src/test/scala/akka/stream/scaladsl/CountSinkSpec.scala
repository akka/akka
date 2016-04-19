/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.Done
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, IOResult }
import akka.testkit.AkkaSpec

import scala.concurrent.Await
import scala.util.Success

class CountSinkSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Sink.count" must {
    "count properly for successful upstream." in {
      val result = Await.result(
        Source(1 to 10)
          .runWith(Sink.count), remainingOrDefault)

      result should equal(IOResult(10, Success(Done)))
    }

    "count properly for failed upstream." in {
      val result = Await.result(
        Source(1 to 4)
          .map(d ⇒ 1 / (4 - d))
          .runWith(Sink.count), remainingOrDefault)

      result.count == 3 && result.status.isFailure
    }

    "count properly for empty source." in {
      val result = Await.result(
        Source
          .empty
          .runWith(Sink.count), remainingOrDefault)

      result should equal(IOResult(0, Success(Done)))
    }
  }
}
