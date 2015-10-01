/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._

import akka.stream.{ OverflowStrategy, ActorMaterializerSettings }
import akka.stream.ActorMaterializer
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class GraphSplitSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Split" must {
    import FlowGraph.Implicits._

    "split to two subscribers on a predicate" in assertAllStagesStopped {
      val left = TestSubscriber.manualProbe[Int]()
      val right = TestSubscriber.manualProbe[Int]()

      FlowGraph.closed() { implicit b ⇒
        val split = b.add(Split.using[Int](_ % 2 == 0))
        Source(List(1, 2, 3)) ~> split.in
        split.out0 ~> Flow[Int].buffer(16, OverflowStrategy.backpressure).map(_ * 2) ~> Sink(left)
        split.out1 ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink(right)
      }.run()

      val sub1 = left.expectSubscription()
      val sub2 = right.expectSubscription()
      sub1.request(10)
      sub2.request(10)
      left.expectNext(1 * 2)
      right.expectNext(2)
      left.expectNext(3 * 2)
      left.expectComplete()
      right.expectComplete()
    }

    "split to two subscribers on a function" in assertAllStagesStopped {
      val left = TestSubscriber.manualProbe[Int]()
      val right = TestSubscriber.manualProbe[String]()

      FlowGraph.closed() { implicit b ⇒
        val split = b.add(Split((i: Int) ⇒ {
          if (i % 2 == 0) Right[Int, String](i.toString)
          else Left[Int, String](i)
        }))
        Source(List(1, 2, 3)) ~> split.in
        split.out0 ~> Flow[Int].buffer(16, OverflowStrategy.backpressure).map(_ * 2) ~> Sink(left)
        split.out1 ~> Flow[String].buffer(16, OverflowStrategy.backpressure) ~> Sink(right)
      }.run()

      val sub1 = left.expectSubscription()
      val sub2 = right.expectSubscription()
      sub1.request(10)
      sub2.request(10)
      left.expectNext(1 * 2)
      sub1.request(10)
      right.expectNext("2")
      left.expectNext(3 * 2)
      left.expectComplete()
      right.expectComplete()
    }

  }

}
