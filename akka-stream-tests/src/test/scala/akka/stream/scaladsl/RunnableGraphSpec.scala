/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.Attributes
import akka.stream.javadsl
import akka.stream.testkit.StreamSpec

class RunnableGraphSpec extends StreamSpec {

  "A RunnableGraph" must {

    "suitably override attribute handling methods" in {
      import Attributes._
      val r: RunnableGraph[NotUsed] =
        RunnableGraph.fromGraph(Source.empty.to(Sink.ignore)).async.addAttributes(none).named("useless")

      r.traversalBuilder.attributes.get[Name] shouldEqual Some(Name("useless"))
      r.traversalBuilder.attributes.get[AsyncBoundary.type] shouldEqual (Some(AsyncBoundary))
    }

    "allow conversion from scala to java" in {
      val runnable: javadsl.RunnableGraph[NotUsed] = Source.empty.to(Sink.ignore).asJava
      runnable.run(system) shouldBe NotUsed
    }

    "allow conversion from java to scala" in {
      val runnable: RunnableGraph[NotUsed] = javadsl.Source.empty.to(javadsl.Sink.ignore).asScala
      runnable.run() shouldBe NotUsed
    }

  }
}
