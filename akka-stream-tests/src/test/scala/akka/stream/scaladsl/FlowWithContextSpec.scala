/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.StreamSpec

class FlowWithContextSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  "A FlowWithContext" must {

    "get created from Flow.asFlowWithContext" in {
      val flow = Flow[Message].map { case m => m.copy(data = m.data + "z") }
      val flowWithContext = flow.asFlowWithContext((m: Message, o: Long) => Message(m.data, o)) { m =>
        m.offset
      }

      val msg = Message("a", 1L)
      Source(Vector(msg))
        .asSourceWithContext(_.offset)
        .via(flowWithContext)
        .asSource
        .runWith(TestSink.probe[(Message, Long)])
        .request(1)
        .expectNext(((Message("az", 1L), 1L)))
        .expectComplete()
    }
  }
}
