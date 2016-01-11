package akka.stream.scaladsl

import akka.stream.{ ClosedShape, ActorMaterializer, ActorMaterializerSettings, FlowShape }
import akka.stream.testkit.AkkaSpec

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class GraphPartialSpec extends AkkaSpec {
  import GraphDSL.Implicits._

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "FlowFlowGraph.partial" must {
    import GraphDSL.Implicits._

    "be able to build and reuse simple partial graphs" in {
      val doubler = GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](2))
        val zip = b.add(ZipWith((a: Int, b: Int) ⇒ a + b))

        bcast.out(0) ~> zip.in0
        bcast.out(1) ~> zip.in1
        FlowShape(bcast.in, zip.out)
      }

      val (_, _, result) = RunnableGraph.fromGraph(GraphDSL.create(doubler, doubler, Sink.head[Seq[Int]])(Tuple3.apply) { implicit b ⇒
        (d1, d2, sink) ⇒
          Source(List(1, 2, 3)) ~> d1.in
          d1.out ~> d2.in
          d2.out.grouped(100) ~> sink.in
          ClosedShape
      }).run()

      Await.result(result, 3.seconds) should be(List(4, 8, 12))
    }

    "be able to build and reuse simple materializing partial graphs" in {
      val doubler = GraphDSL.create(Sink.head[Seq[Int]]) { implicit b ⇒
        sink ⇒
          val bcast = b.add(Broadcast[Int](3))
          val zip = b.add(ZipWith((a: Int, b: Int) ⇒ a + b))

          bcast.out(0) ~> zip.in0
          bcast.out(1) ~> zip.in1
          bcast.out(2).grouped(100) ~> sink.in
          FlowShape(bcast.in, zip.out)
      }

      val (sub1, sub2, result) = RunnableGraph.fromGraph(GraphDSL.create(doubler, doubler, Sink.head[Seq[Int]])(Tuple3.apply) { implicit b ⇒
        (d1, d2, sink) ⇒
          Source(List(1, 2, 3)) ~> d1.in
          d1.out ~> d2.in
          d2.out.grouped(100) ~> sink.in
          ClosedShape
      }).run()

      Await.result(result, 3.seconds) should be(List(4, 8, 12))
      Await.result(sub1, 3.seconds) should be(List(1, 2, 3))
      Await.result(sub2, 3.seconds) should be(List(2, 4, 6))
    }

    "be able to build and reuse complex materializing partial graphs" in {
      val summer = Sink.fold[Int, Int](0)(_ + _)

      val doubler = GraphDSL.create(summer, summer)(Tuple2.apply) { implicit b ⇒
        (s1, s2) ⇒
          val bcast = b.add(Broadcast[Int](3))
          val bcast2 = b.add(Broadcast[Int](2))
          val zip = b.add(ZipWith((a: Int, b: Int) ⇒ a + b))

          bcast.out(0) ~> zip.in0
          bcast.out(1) ~> zip.in1
          bcast.out(2) ~> s1.in

          zip.out ~> bcast2.in
          bcast2.out(0) ~> s2.in

          FlowShape(bcast.in, bcast2.out(1))
      }

      val (sub1, sub2, result) = RunnableGraph.fromGraph(GraphDSL.create(doubler, doubler, Sink.head[Seq[Int]])(Tuple3.apply) { implicit b ⇒
        (d1, d2, sink) ⇒
          Source(List(1, 2, 3)) ~> d1.in
          d1.out ~> d2.in
          d2.out.grouped(100) ~> sink.in
          ClosedShape
      }).run()

      Await.result(result, 3.seconds) should be(List(4, 8, 12))
      Await.result(sub1._1, 3.seconds) should be(6)
      Await.result(sub1._2, 3.seconds) should be(12)
      Await.result(sub2._1, 3.seconds) should be(12)
      Await.result(sub2._2, 3.seconds) should be(24)
    }

    "be able to expose the ports of imported graphs" in {
      val p = GraphDSL.create(Flow[Int].map(_ + 1)) { implicit b ⇒
        flow ⇒
          FlowShape(flow.in, flow.out)
      }

      val fut = RunnableGraph.fromGraph(GraphDSL.create(Sink.head[Int], p)(Keep.left) { implicit b ⇒
        (sink, flow) ⇒
          import GraphDSL.Implicits._
          Source.single(0) ~> flow.in
          flow.out ~> sink.in
          ClosedShape
      }).run()

      Await.result(fut, 3.seconds) should be(1)

    }
  }

}
