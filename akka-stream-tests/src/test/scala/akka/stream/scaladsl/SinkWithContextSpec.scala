/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec

class SinkWithContextSpec extends StreamSpec {
  "A SinkWithContext" must {

    "respect ordering when using SinkWithContext.fromDataAndContext" in {

      val dataSink = Sink.collection[Int, List[Int]]
      val contextSink = Sink.collection[Int, List[Int]]

      val sink = SinkWithContext.fromDataAndContext(dataSink, contextSink, Keep.zipFuture)(Concat(_))

      val source = SourceWithContext.fromTuples(Source(List((1, 2), (3, 4), (5, 6))))

      source.runWith(sink).futureValue shouldEqual List((1, 2), (3, 4), (5, 6))

    }

  }

}
