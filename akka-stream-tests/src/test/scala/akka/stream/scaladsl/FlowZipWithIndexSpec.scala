/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.{ StreamSpec, TestSubscriber }
import com.github.ghik.silencer.silent

@silent // keep unused imports
class FlowZipWithIndexSpec extends StreamSpec {

//#zip-with-index
  import akka.stream.scaladsl.Source
  import akka.stream.scaladsl.Sink

//#zip-with-index
  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 2, maxSize = 16)

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

    "work in fruit example" in {
      //#zip-with-index
      Source(List("apple", "orange", "banana")).zipWithIndex.runWith(Sink.foreach(println))
      // this will print ('apple', 0), ('orange', 1), ('banana', 2)
      //#zip-with-index
    }

  }
}
