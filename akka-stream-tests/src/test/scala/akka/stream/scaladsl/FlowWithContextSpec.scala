/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.TestSink

import scala.util.control.NoStackTrace

class FlowWithContextSpec extends StreamSpec {

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

    "be able to map materialized value via FlowWithContext.mapMaterializedValue" in {
      val materializedValue = "MatedValue"
      val mapMaterializedValueFlow = FlowWithContext[Message, Long].mapMaterializedValue(_ => materializedValue)

      val msg = Message("a", 1L)
      val (matValue, probe) = Source(Vector(msg))
        .mapMaterializedValue(_ => 42)
        .asSourceWithContext(_.offset)
        .viaMat(mapMaterializedValueFlow)(Keep.both)
        .toMat(TestSink.probe[(Message, Long)])(Keep.both)
        .run
      matValue shouldBe (42 -> materializedValue)
      probe.request(1).expectNext(((Message("a", 1L), 1L))).expectComplete()
    }

    "be able to map error via FlowWithContext.mapError" in {
      val ex = new RuntimeException("ex") with NoStackTrace
      val boom = new Exception("BOOM!") with NoStackTrace
      val mapErrorFlow = FlowWithContext[Message, Long]
        .map {
          case m @ Message(_, offset) => if (offset == 3) throw ex else m
        }
        .mapError { case _: Throwable => boom }

      Source(1L to 4L)
        .map { offset =>
          Message("a", offset)
        }
        .asSourceWithContext(_.offset)
        .via(mapErrorFlow)
        .runWith(TestSink.probe[(Message, Long)])
        .request(3)
        .expectNext((Message("a", 1L), 1L))
        .expectNext((Message("a", 2L), 2L))
        .expectError(boom)
    }
  }
}
