/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.snapshot

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.StreamSpec

import scala.concurrent.duration._

class MaterializerStateSpec extends StreamSpec {

  "The MaterializerSnapshotting" must {

    "snapshot a running stream" in {
      implicit val mat = ActorMaterializer()
      Source.maybe[Int].map(_.toString).zipWithIndex.runWith(Sink.seq)

      awaitAssert({
        val snapshot = MaterializerState.streamSnapshots(mat).futureValue

        snapshot should have size (1)
        snapshot.head.activeInterpreters should have size (1)
        snapshot.head.activeInterpreters.head.logics should have size (4) // all 4 operators
      }, 3.seconds)
    }
  }

}
