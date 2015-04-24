/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._

class StreamTestKitDocSpec extends AkkaSpec {

  implicit val mat = ActorFlowMaterializer()

  "test source probe" in {

    //#test-source-probe
    TestSource.probe[Int]
      .toMat(Sink.cancelled)(Keep.left)
      .run()
      .expectCancellation()
    //#test-source-probe
  }

  "test sink probe" in {

    //#test-sink-probe
    Source(1 to 4)
      .filter(_ % 2 == 0)
      .map(_ * 2)
      .runWith(TestSink.probe[Int])
      .request(2)
      .expectNext(4, 8)
      .expectComplete()
    //#test-sink-probe
  }

}
