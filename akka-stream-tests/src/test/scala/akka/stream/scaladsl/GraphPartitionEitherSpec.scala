/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ ClosedShape, OverflowStrategy }

import akka.stream.testkit._

import scala.concurrent.Await
import scala.concurrent.duration._

class GraphPartitionEitherSpec extends StreamSpec("""
    akka.stream.materializer.initial-input-buffer-size = 2
  """) {

  "A partition either" must {
    import GraphDSL.Implicits._

    "partition to two subscribers" in {

      val (s1, s2) = RunnableGraph
        .fromGraph(GraphDSL.createGraph(Sink.seq[String], Sink.seq[Int])(Tuple2.apply) { implicit b => (sink1, sink2) =>
          val partition = b.add(PartitionEither[String, Int]())
          Source(List(Left("one"), Right(2), Left("three"), Right(4))) ~> partition.in
          partition.out0 ~> sink1.in
          partition.out1 ~> sink2.in
          ClosedShape
        })
        .run()

      s1.futureValue.toSet should ===(Set("one", "three"))
      s2.futureValue.toSet should ===(Set(2, 4))
    }

    "complete stage after upstream completes" in {
      val c1 = TestSubscriber.probe[String]()
      val c2 = TestSubscriber.probe[Int]()

      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit b =>
          val partition = b.add(PartitionEither[String, Int]())
          Source(List(Left("forty"), Left("two"), Right(4), Right(2))) ~> partition.in
          partition.out0 ~> Sink.fromSubscriber(c1)
          partition.out1 ~> Sink.fromSubscriber(c2)
          ClosedShape
        })
        .run()

      c1.request(2)
      c2.request(2)
      c1.expectNext("forty")
      c1.expectNext("two")
      c2.expectNext(4)
      c2.expectNext(2)
      c1.expectComplete()
      c2.expectComplete()

    }

    "remember first pull even though first element targeted another out" in {
      val c1 = TestSubscriber.probe[String]()
      val c2 = TestSubscriber.probe[Int]()

      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit b =>
          val partition = b.add(PartitionEither[String, Int]())
          Source(List(Right(6), Left("three"))) ~> partition.in
          partition.out0 ~> Sink.fromSubscriber(c1)
          partition.out1 ~> Sink.fromSubscriber(c2)
          ClosedShape
        })
        .run()

      c1.request(1)
      c1.expectNoMessage(1.seconds)
      c2.request(1)
      c2.expectNext(6)
      c1.expectNext("three")
      c1.expectComplete()
      c2.expectComplete()
    }

    "cancel upstream when all downstreams cancel if eagerCancel is false" in {
      val p1 = TestPublisher.probe[Either[Int, Int]]()
      val c1 = TestSubscriber.probe[Int]()
      val c2 = TestSubscriber.probe[Int]()

      def either(i: Int): Either[Int, Int] = Either.cond(i >= 6, i, i)

      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit b =>
          val partition = b.add(new PartitionEither[Int, Int](false))
          Source.fromPublisher(p1.getPublisher) ~> partition.in
          partition.out0 ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink.fromSubscriber(c1)
          partition.out1 ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink.fromSubscriber(c2)
          ClosedShape
        })
        .run()

      val p1Sub = p1.expectSubscription()
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(3)
      sub2.request(3)
      p1Sub.sendNext(either(1))
      p1Sub.sendNext(either(8))
      c1.expectNext(1)
      c2.expectNext(8)
      p1Sub.sendNext(either(2))
      c1.expectNext(2)
      sub1.cancel()
      p1Sub.sendNext(either(9))
      c2.expectNext(9)
      sub2.cancel()
      p1Sub.expectCancellation()
    }

    "cancel upstream when any downstream cancel if eagerCancel is true" in {
      val p1 = TestPublisher.probe[Either[Int, Int]]()
      val c1 = TestSubscriber.probe[Int]()
      val c2 = TestSubscriber.probe[Int]()

      def either(i: Int): Either[Int, Int] = Either.cond(i >= 6, i, i)

      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit b =>
          val partition = b.add(new PartitionEither[Int, Int](true))
          Source.fromPublisher(p1.getPublisher) ~> partition.in
          partition.out0 ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink.fromSubscriber(c1)
          partition.out1 ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink.fromSubscriber(c2)
          ClosedShape
        })
        .run()

      val p1Sub = p1.expectSubscription()
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(3)
      sub2.request(3)
      p1Sub.sendNext(either(1))
      p1Sub.sendNext(either(8))
      c1.expectNext(1)
      c2.expectNext(8)
      sub1.cancel()
      p1Sub.expectCancellation()
    }

    "handle upstream completes and downstream cancel" in {
      val c1 = TestSubscriber.probe[String]()
      val c2 = TestSubscriber.probe[String]()

      def either(s: String): Either[String, String] =
        Either.cond(s != "a" && s != "b", s, s)

      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit b =>
          val partition = b.add(PartitionEither[String, String]())
          Source(List("a", "b", "c", "d").map(either)) ~> partition.in
          partition.out0 ~> Sink.fromSubscriber(c1)
          partition.out1 ~> Sink.fromSubscriber(c2)
          ClosedShape
        })
        .run()

      c1.request(10)
      c2.request(1)
      c1.expectNext("a")
      c1.expectNext("b")
      c2.expectNext("c")
      c2.cancel()
      c1.expectComplete()
    }

    "handle upstream completes and downstream pulls" in {
      val c1 = TestSubscriber.probe[String]()
      val c2 = TestSubscriber.probe[String]()

      def either(s: String): Either[String, String] =
        Either.cond(s != "a" && s != "b", s, s)

      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit b =>
          val partition = b.add(PartitionEither[String, String]())
          Source(List("a", "b", "c").map(either)) ~> partition.in
          partition.out0 ~> Sink.fromSubscriber(c1)
          partition.out1 ~> Sink.fromSubscriber(c2)
          ClosedShape
        })
        .run()

      c1.request(10)
      // no demand from c2 yet
      c1.expectNext("a")
      c1.expectNext("b")
      c2.request(1)
      c2.expectNext("c")

      c1.expectComplete()
      c2.expectComplete()
    }

    "work with merge" in {
      val s = Sink.seq[Int]
      val input = Set(5, 2, 9, 1, 1, 1, 10)

      def either(l: Int): Either[Int, Int] = Either.cond(l >= 4, l, l)

      val g = RunnableGraph.fromGraph(GraphDSL.createGraph(s) { implicit b => sink =>
        val partition = b.add(PartitionEither[Int, Int]())
        val merge = b.add(Merge[Int](2))
        Source(input).map(either) ~> partition.in
        partition.out0 ~> merge.in(0)
        partition.out1 ~> merge.in(1)
        merge.out ~> sink.in

        ClosedShape
      })

      val result = Await.result(g.run(), remainingOrDefault)

      result.toSet should be(input)

    }

    "stage completion is waiting for pending output" in {

      val c1 = TestSubscriber.probe[Int]()
      val c2 = TestSubscriber.probe[Int]()

      def either(l: Int): Either[Int, Int] = Either.cond(l >= 6, l, l)

      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit b =>
          val partition = b.add(PartitionEither[Int, Int]())
          Source(List(6)).map(either) ~> partition.in
          partition.out0 ~> Sink.fromSubscriber(c1)
          partition.out1 ~> Sink.fromSubscriber(c2)
          ClosedShape
        })
        .run()

      c1.request(1)
      c1.expectNoMessage(1.second)
      c2.request(1)
      c2.expectNext(6)
      c1.expectComplete()
      c2.expectComplete()
    }
  }
}
