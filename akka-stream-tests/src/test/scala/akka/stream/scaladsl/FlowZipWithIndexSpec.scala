/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.{ StreamSpec, TestSubscriber }

class FlowZipWithIndexSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A ZipWithIndex for Flow " must {

    "work in the happy case" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[(Int, Long)]()
      Source(7 to 10).zipWithIndex.runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext((7, 0L))
      probe.expectNext((8, 1L))

      subscription.request(1)
      probe.expectNext((9, 2L))
      subscription.request(1)
      probe.expectNext((10, 3L))

      probe.expectComplete()
    }

  }
}
