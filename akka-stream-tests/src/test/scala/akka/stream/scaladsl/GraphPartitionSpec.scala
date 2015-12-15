/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.{ OverflowStrategy, ActorMaterializer, ActorMaterializerSettings, ClosedShape }
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

class GraphPartitionSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A partition" must {
    import GraphDSL.Implicits._

    "partition to three subscribers" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()
      val c3 = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](3, {
          case g if (g > 3)  ⇒ 0
          case l if (l < 3)  ⇒ 1
          case e if (e == 3) ⇒ 2
        }))
        Source(List(1, 2, 3, 4, 5)) ~> partition.in
        partition.out(0) ~> Sink(c1)
        partition.out(1) ~> Sink(c2)
        partition.out(2) ~> Sink(c3)
        ClosedShape
      }).run()

      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      val sub3 = c3.expectSubscription()
      sub2.request(2)
      sub1.request(2)
      sub3.request(1)
      c2.expectNext(1)
      c2.expectNext(2)
      c3.expectNext(3)
      c1.expectNext(4)
      c1.expectNext(5)
      c1.expectComplete()
      c2.expectComplete()
      c3.expectComplete()
    }

    "remember first pull even though first element targeted another out" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 6 ⇒ 0; case _ ⇒ 1 }))
        Source(List(6, 3)) ~> partition.in
        partition.out(0) ~> Sink(c1)
        partition.out(1) ~> Sink(c2)
        ClosedShape
      }).run()

      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      c1.expectNoMsg(1.seconds)
      sub2.request(1)
      c2.expectNext(6)
      c1.expectNext(3)
    }

    "cancel upstream when downstreams cancel" in assertAllStagesStopped {
      val p1 = TestPublisher.manualProbe[Int]()
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 6 ⇒ 0; case _ ⇒ 1 }))
        Source(p1.getPublisher) ~> partition.in
        partition.out(0) ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink(c1)
        partition.out(1) ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink(c2)
        ClosedShape
      }).run()

      val p1Sub = p1.expectSubscription()
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(3)
      sub2.request(3)
      p1.expectRequest(p1Sub, 16)
      p1Sub.sendNext(1)
      p1Sub.sendNext(8)
      c1.expectNext(1)
      c2.expectNext(8)
      p1Sub.sendNext(2)
      c1.expectNext(2)
      sub1.cancel()
      sub2.cancel()
      p1Sub.expectCancellation()
    }

    "work with merge" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()
      val probe = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 4 ⇒ 0; case _ ⇒ 1 }))
        val merge = b.add(Merge[Int](2))
        Source(List(5, 2, 9, 1, 1, 1, 10)) ~> partition.in
        partition.out(0) ~> merge.in(0)
        partition.out(1) ~> merge.in(1)
        merge.out ~> Sink(probe)

        ClosedShape
      }).run()

      val subscription = probe.expectSubscription()

      var collected = Set.empty[Int]
      for (_ ← 1 to 7) {
        subscription.request(1)
        collected += probe.expectNext()
      }

      collected should be(Set(5, 2, 9, 1, 1, 1, 10))
      probe.expectComplete()

    }

  }
}
