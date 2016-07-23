/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils.TE
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.AkkaSpec

class FlowOrElseSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)

  "An OrElse flow" should {

    "pass elements from the first input" in {
      val source1 = Source(Vector(1, 2, 3))
      val source2 = Source(Vector(4, 5, 6))

      val sink = Sink.fold[Vector[Int], Int](Vector[Int]())((acc, elem) ⇒ acc :+ elem)

      source1.orElse(source2).runWith(sink).futureValue shouldEqual Vector(1, 2, 3)
    }

    "pass elements from the second input if the first completes with no elements emitted" in {
      val source1 = Source.empty[Int]
      val source2 = Source(Vector(4, 5, 6))

      val sink = Sink.fold[Vector[Int], Int](Vector[Int]())((acc, elem) ⇒ acc :+ elem)

      source1.orElse(source2).runWith(sink).futureValue shouldEqual Vector(4, 5, 6)
    }

    "pass elements from input one through and cancel input 2" in new OrElseProbedFlow {
      outProbe.request(1)
      inProbe1.expectRequest()
      inProbe1.sendNext('a')
      outProbe.expectNext('a')
      inProbe1.sendComplete()
      inProbe2.expectCancellation()
      outProbe.expectComplete()
    }

    "pass elements from input two when input 1 has completed without elements" in new OrElseProbedFlow {
      outProbe.request(1)
      inProbe1.sendComplete()
      inProbe2.expectRequest()
      inProbe2.sendNext('a')
      outProbe.expectNext('a')
      inProbe2.sendComplete()
      outProbe.expectComplete()
    }

    "complete when both inputs completes without emitting elements" in new OrElseProbedFlow {
      outProbe.ensureSubscription()
      inProbe1.sendComplete()
      inProbe2.sendComplete()
      outProbe.expectComplete()
    }

    "complete when both inputs completes without emitting elements, regardless of order" in new OrElseProbedFlow {
      outProbe.ensureSubscription()
      inProbe2.sendComplete()
      inProbe1.sendComplete()
      outProbe.expectComplete()
    }

    "fail when input 1 fails" in new OrElseProbedFlow {
      outProbe.ensureSubscription()
      inProbe1.sendError(TE("in1 failed"))
      inProbe2.expectCancellation()
      outProbe.expectError()
    }

    "fail when input 2 fails" in new OrElseProbedFlow {
      outProbe.ensureSubscription()
      inProbe2.sendError(TE("in2 failed"))
      inProbe1.expectCancellation()
      outProbe.expectError()
    }

    trait OrElseProbedFlow {
      val inProbe1 = TestPublisher.probe[Char]()
      val source1 = Source.fromPublisher(inProbe1)
      val inProbe2 = TestPublisher.probe[Char]()
      val source2 = Source.fromPublisher(inProbe2)

      val outProbe = TestSubscriber.probe[Char]()
      val sink = Sink.fromSubscriber(outProbe)

      source1.orElse(source2).runWith(sink)
    }

  }

}
