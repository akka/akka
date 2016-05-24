/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.{ OverflowStrategy, ActorMaterializer, ActorMaterializerSettings, ClosedShape }
import akka.stream.testkit.Utils._
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.testkit.AkkaSpec

class GraphPartitionSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A partition" must {
    import GraphDSL.Implicits._

    "partition to three subscribers" in assertAllStagesStopped {

      val (s1, s2, s3) = RunnableGraph.fromGraph(GraphDSL.create(Sink.seq[Int], Sink.seq[Int], Sink.seq[Int])(Tuple3.apply) { implicit b ⇒ (sink1, sink2, sink3) ⇒
        val partition = b.add(Partition[Int](3, {
          case g if (g > 3)  ⇒ 0
          case l if (l < 3)  ⇒ 1
          case e if (e == 3) ⇒ 2
        }))
        Source(List(1, 2, 3, 4, 5)) ~> partition.in
        partition.out(0) ~> sink1.in
        partition.out(1) ~> sink2.in
        partition.out(2) ~> sink3.in
        ClosedShape
      }).run()

      s1.futureValue.toSet should ===(Set(4, 5))
      s2.futureValue.toSet should ===(Set(1, 2))
      s3.futureValue.toSet should ===(Set(3))

    }

    "complete stage after upstream completes" in assertAllStagesStopped {
      val c1 = TestSubscriber.probe[String]()
      val c2 = TestSubscriber.probe[String]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[String](2, {
          case s if (s.length > 4) ⇒ 0
          case _                   ⇒ 1
        }))
        Source(List("this", "is", "just", "another", "test")) ~> partition.in
        partition.out(0) ~> Sink.fromSubscriber(c1)
        partition.out(1) ~> Sink.fromSubscriber(c2)
        ClosedShape
      }).run()

      c1.request(1)
      c2.request(4)
      c1.expectNext("another")
      c2.expectNext("this")
      c2.expectNext("is")
      c2.expectNext("just")
      c2.expectNext("test")
      c1.expectComplete()
      c2.expectComplete()

    }

    "remember first pull even though first element targeted another out" in assertAllStagesStopped {
      val c1 = TestSubscriber.probe[Int]()
      val c2 = TestSubscriber.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 6 ⇒ 0; case _ ⇒ 1 }))
        Source(List(6, 3)) ~> partition.in
        partition.out(0) ~> Sink.fromSubscriber(c1)
        partition.out(1) ~> Sink.fromSubscriber(c2)
        ClosedShape
      }).run()

      c1.request(1)
      c1.expectNoMsg(1.seconds)
      c2.request(1)
      c2.expectNext(6)
      c1.expectNext(3)
      c1.expectComplete()
      c2.expectComplete()
    }

    "cancel upstream when downstreams cancel" in assertAllStagesStopped {
      val p1 = TestPublisher.probe[Int]()
      val c1 = TestSubscriber.probe[Int]()
      val c2 = TestSubscriber.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 6 ⇒ 0; case _ ⇒ 1 }))
        Source.fromPublisher(p1.getPublisher) ~> partition.in
        partition.out(0) ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink.fromSubscriber(c1)
        partition.out(1) ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink.fromSubscriber(c2)
        ClosedShape
      }).run()

      val p1Sub = p1.expectSubscription()
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(3)
      sub2.request(3)
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
      val s = Sink.seq[Int]
      val input = Set(5, 2, 9, 1, 1, 1, 10)

      val g = RunnableGraph.fromGraph(GraphDSL.create(s) { implicit b ⇒ sink ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 4 ⇒ 0; case _ ⇒ 1 }))
        val merge = b.add(Merge[Int](2))
        Source(input) ~> partition.in
        partition.out(0) ~> merge.in(0)
        partition.out(1) ~> merge.in(1)
        merge.out ~> sink.in

        ClosedShape
      })

      val result = Await.result(g.run(), remainingOrDefault)

      result.toSet should be(input)

    }

    "stage completion is waiting for pending output" in assertAllStagesStopped {

      val c1 = TestSubscriber.probe[Int]()
      val c2 = TestSubscriber.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 6 ⇒ 0; case _ ⇒ 1 }))
        Source(List(6)) ~> partition.in
        partition.out(0) ~> Sink.fromSubscriber(c1)
        partition.out(1) ~> Sink.fromSubscriber(c2)
        ClosedShape
      }).run()

      c1.request(1)
      c1.expectNoMsg(1.second)
      c2.request(1)
      c2.expectNext(6)
      c1.expectComplete()
      c2.expectComplete()
    }

    "must fail stage if partitioner outcome is out of bound" in assertAllStagesStopped {

      val c1 = TestSubscriber.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val partition = b.add(Partition[Int](2, { case l if l < 0 ⇒ -1; case _ ⇒ 0 }))
        Source(List(-3)) ~> partition.in
        partition.out(0) ~> Sink.fromSubscriber(c1)
        partition.out(1) ~> Sink.ignore
        ClosedShape
      }).run()

      c1.request(1)
      c1.expectError(Partition.PartitionOutOfBoundsException("partitioner must return an index in the range [0,1]. returned: [-1] for input [java.lang.Integer]."))
    }

  }
}
