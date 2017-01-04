/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.{ ActorMaterializer, Attributes }
import akka.stream.testkit.StreamSpec

class RunnableGraphSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()

  "A RunnableGraph" must {

    "suitably override attribute handling methods" in {
      import Attributes._
      val r: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(Source.empty.to(Sink.ignore)).async.addAttributes(none).named("useless")

      r.module.attributes.getFirst[Name] shouldEqual Some(Name("useless"))
      r.module.attributes.getFirst[AsyncBoundary.type] shouldEqual (Some(AsyncBoundary))
    }

  }
}
