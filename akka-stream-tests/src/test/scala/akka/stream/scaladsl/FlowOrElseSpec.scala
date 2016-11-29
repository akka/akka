/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.AkkaSpec

import scala.collection.immutable.Seq

class FlowOrElseSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)

  "An OrElse flow" should {

    "pass elements from the first input" in {
      val source1 = Source(Seq(1, 2, 3))
      val source2 = Source(Seq(4, 5, 6))

      val sink = Sink.seq[Int]

      source1.orElse(source2).runWith(sink).futureValue shouldEqual Seq(1, 2, 3)
    }

    "pass elements from the second input if the first completes with no elements emitted" in {
      val source1 = Source.empty[Int]
      val source2 = Source(Seq(4, 5, 6))
      val sink = Sink.seq[Int]

      source1.orElse(source2).runWith(sink).futureValue shouldEqual Seq(4, 5, 6)
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

    "pass elements from input two when input 1 has completed without elements (lazyEmpty)" in {
      val inProbe1 = TestPublisher.lazyEmpty[Char]
      val source1 = Source.fromPublisher(inProbe1)
      val inProbe2 = TestPublisher.probe[Char]()
      val source2 = Source.fromPublisher(inProbe2)
      val outProbe = TestSubscriber.probe[Char]()
      val sink = Sink.fromSubscriber(outProbe)

      source1.orElse(source2).runWith(sink)
      outProbe.request(1)
      inProbe2.expectRequest()
      inProbe2.sendNext('a')
      outProbe.expectNext('a')
      inProbe2.sendComplete()

      outProbe.expectComplete()
    }

    "pass all available requested elements from input two when input 1 has completed without elements" in new OrElseProbedFlow {
      outProbe.request(5)

      inProbe1.sendComplete()

      inProbe2.expectRequest()
      inProbe2.sendNext('a')
      outProbe.expectNext('a')

      inProbe2.sendNext('b')
      outProbe.expectNext('b')

      inProbe2.sendNext('c')
      outProbe.expectNext('c')

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
      outProbe.expectNoMsg(200.millis) // make sure it did not complete here
      inProbe1.sendComplete()
      outProbe.expectComplete()
    }

    "continue passing primary through when secondary completes" in new OrElseProbedFlow {
      outProbe.ensureSubscription()
      outProbe.request(1)
      inProbe2.sendComplete()

      inProbe1.expectRequest()
      inProbe1.sendNext('a')
      outProbe.expectNext('a')

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
