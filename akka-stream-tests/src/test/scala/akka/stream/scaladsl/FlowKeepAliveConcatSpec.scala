/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.{ StreamSpec, TestSubscriber, TestPublisher, Utils }
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowKeepAliveConcatSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  val sampleSource = Source((1 to 10).grouped(3).toVector)
  val expand = (lst: IndexedSeq[Int]) ⇒ lst.toList.map(Vector(_))

  "keepAliveConcat" must {

    "not emit additional elements if upstream is fast enough" in Utils.assertAllStagesStopped {
      Await.result(
        sampleSource
          .keepAliveConcat(5, 1.second, expand)
          .grouped(1000)
          .runWith(Sink.head),
        3.seconds
      ).flatten should ===(1 to 10)
    }

    "emit elements periodically after silent periods" in Utils.assertAllStagesStopped {
      val sourceWithIdleGap = Source((1 to 5).grouped(3).toList) ++
        Source((6 to 10).grouped(3).toList).initialDelay(2.second)

      Await.result(
        sourceWithIdleGap
          .keepAliveConcat(5, 0.6.seconds, expand)
          .grouped(1000)
          .runWith(Sink.head),
        3.seconds
      ).flatten should ===(1 to 10)
    }

    "immediately pull upstream" in {
      val upstream = TestPublisher.probe[Vector[Int]]()
      val downstream = TestSubscriber.probe[Vector[Int]]()

      Source.fromPublisher(upstream).keepAliveConcat(2, 1.second, expand).runWith(Sink.fromSubscriber(downstream))

      downstream.request(1)

      upstream.sendNext(Vector(1))
      downstream.expectNext(Vector(1))

      upstream.sendComplete()
      downstream.expectComplete()
    }

    "immediately pull upstream after busy period" in {
      val upstream = TestPublisher.probe[IndexedSeq[Int]]()
      val downstream = TestSubscriber.probe[IndexedSeq[Int]]()

      (sampleSource ++ Source.fromPublisher(upstream))
        .keepAliveConcat(2, 1.second, expand)
        .runWith(Sink.fromSubscriber(downstream))

      downstream.request(10)
      downstream.expectNextN((1 to 3).grouped(1).toVector ++ (4 to 10).grouped(3).toVector)

      downstream.request(1)

      upstream.sendNext(Vector(1))
      downstream.expectNext(Vector(1))

      upstream.sendComplete()
      downstream.expectComplete()
    }

    "work if timer fires before initial request after busy period" in {
      val upstream = TestPublisher.probe[IndexedSeq[Int]]()
      val downstream = TestSubscriber.probe[IndexedSeq[Int]]()

      (sampleSource ++ Source.fromPublisher(upstream))
        .keepAliveConcat(2, 1.second, expand)
        .runWith(Sink.fromSubscriber(downstream))

      downstream.request(10)
      downstream.expectNextN((1 to 3).grouped(1).toVector ++ (4 to 10).grouped(3).toVector)

      downstream.expectNoMsg(1.5.second)
      downstream.request(1)

      upstream.sendComplete()
      downstream.expectComplete()
    }

  }

}
