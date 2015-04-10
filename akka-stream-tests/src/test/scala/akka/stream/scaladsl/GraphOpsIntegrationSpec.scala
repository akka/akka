package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit.{ OnNext, SubscriberProbe }
import akka.util.ByteString
import akka.stream.{ Inlet, Outlet, Shape, Graph }
import org.scalactic.ConversionCheckedTripleEquals
import akka.stream.OperationAttributes

object GraphOpsIntegrationSpec {
  import FlowGraph.Implicits._

  object Shuffle {

    case class ShufflePorts[In, Out](in1: Inlet[In], in2: Inlet[In], out1: Outlet[Out], out2: Outlet[Out]) extends Shape {
      override def inlets: immutable.Seq[Inlet[_]] = List(in1, in2)
      override def outlets: immutable.Seq[Outlet[_]] = List(out1, out2)

      override def deepCopy() = ShufflePorts(
        new Inlet[In](in1.toString), new Inlet[In](in2.toString),
        new Outlet[Out](out1.toString), new Outlet[Out](out2.toString))
      override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): ShufflePorts[In, Out] = {
        assert(inlets.size == this.inlets.size)
        assert(outlets.size == this.outlets.size)
        val i = inlets.asInstanceOf[Seq[Inlet[In]]]
        val o = outlets.asInstanceOf[Seq[Outlet[Out]]]
        ShufflePorts(i(0), i(1), o(0), o(1))
      }
    }

    def apply[In, Out](pipeline: Flow[In, Out, _]): Graph[ShufflePorts[In, Out], Unit] = {
      FlowGraph.partial() { implicit b ⇒
        val merge = b.add(Merge[In](2))
        val balance = b.add(Balance[Out](2))
        merge.out ~> pipeline ~> balance.in
        ShufflePorts(merge.in(0), merge.in(1), balance.out(0), balance.out(1))
      }
    }

  }

}

class GraphOpsIntegrationSpec extends AkkaSpec with ConversionCheckedTripleEquals {
  import akka.stream.scaladsl.GraphOpsIntegrationSpec._
  import FlowGraph.Implicits._

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorFlowMaterializer(settings)

  "FlowGraphs" must {

    "support broadcast - merge layouts" in {
      val resultFuture = FlowGraph.closed(Sink.head[Seq[Int]]) { implicit b ⇒
        (sink) ⇒
          val bcast = b.add(Broadcast[Int](2))
          val merge = b.add(Merge[Int](2))

          Source(List(1, 2, 3)) ~> bcast.in
          bcast.out(0) ~> merge.in(0)
          bcast.out(1).map(_ + 3) ~> merge.in(1)
          merge.out.grouped(10) ~> sink.inlet
      }.run()

      Await.result(resultFuture, 3.seconds).sorted should be(List(1, 2, 3, 4, 5, 6))
    }

    "support balance - merge (parallelization) layouts" in {
      val elements = 0 to 10
      val out = FlowGraph.closed(Sink.head[Seq[Int]]) { implicit b ⇒
        (sink) ⇒
          val balance = b.add(Balance[Int](5))
          val merge = b.add(Merge[Int](5))

          Source(elements) ~> balance.in

          for (i ← 0 until 5) balance.out(i) ~> merge.in(i)

          merge.out.grouped(elements.size * 2) ~> sink.inlet
      }.run()

      Await.result(out, 3.seconds).sorted should be(elements)
    }

    "support wikipedia Topological_sorting 2" in {
      import OperationAttributes.name
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      val seqSink = Sink.head[Seq[Int]]

      val (resultFuture2, resultFuture9, resultFuture10) = FlowGraph.closed(seqSink, seqSink, seqSink)(Tuple3.apply) { implicit b ⇒
        (sink2, sink9, sink10) ⇒
          val b3 = b.add(Broadcast[Int](2))
          val b7 = b.add(Broadcast[Int](2))
          val b11 = b.add(Broadcast[Int](3))
          val m8 = b.add(Merge[Int](2))
          val m9 = b.add(Merge[Int](2))
          val m10 = b.add(Merge[Int](2))
          val m11 = b.add(Merge[Int](2))
          val in3 = Source(List(3))
          val in5 = Source(List(5))
          val in7 = Source(List(7))

          // First layer
          in7 ~> b7.in
          b7.out(0) ~> m11.in(0)
          b7.out(1) ~> m8.in(0)

          in5 ~> m11.in(1)

          in3 ~> b3.in
          b3.out(0) ~> m8.in(1)
          b3.out(1) ~> m10.in(0)

          // Second layer
          m11.out ~> b11.in
          b11.out(0).grouped(1000) ~> sink2.inlet // Vertex 2 is omitted since it has only one in and out
          b11.out(1) ~> m9.in(0)
          b11.out(2) ~> m10.in(1)

          m8.out ~> m9.in(1)

          // Third layer
          m9.out.grouped(1000) ~> sink9.inlet
          m10.out.grouped(1000) ~> sink10.inlet

      }.run()

      Await.result(resultFuture2, 3.seconds).sorted should be(List(5, 7))
      Await.result(resultFuture9, 3.seconds).sorted should be(List(3, 5, 7, 7))
      Await.result(resultFuture10, 3.seconds).sorted should be(List(3, 5, 7))

    }

    "allow adding of flows to sources and sinks to flows" in {

      val resultFuture = FlowGraph.closed(Sink.head[Seq[Int]]) { implicit b ⇒
        (sink) ⇒
          val bcast = b.add(Broadcast[Int](2))
          val merge = b.add(Merge[Int](2))

          Source(List(1, 2, 3)).map(_ * 2) ~> bcast.in
          bcast.out(0) ~> merge.in(0)
          bcast.out(1).map(_ + 3) ~> merge.in(1)
          merge.out.grouped(10) ~> sink.inlet
      }.run()

      Await.result(resultFuture, 3.seconds) should contain theSameElementsAs (Seq(2, 4, 6, 5, 7, 9))
    }

    "be able to run plain flow" in {
      val p = Source(List(1, 2, 3)).runWith(Sink.publisher)
      val s = SubscriberProbe[Int]
      val flow = Flow[Int].map(_ * 2)
      FlowGraph.closed() { implicit builder ⇒
        Source(p) ~> flow ~> Sink(s)
      }.run()
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext(1 * 2)
      s.expectNext(2 * 2)
      s.expectNext(3 * 2)
      s.expectComplete()
    }

    "be possible to use as lego bricks" in {
      val shuffler = Shuffle(Flow[Int].map(_ + 1))

      val f: Future[Seq[Int]] = FlowGraph.closed(shuffler, shuffler, shuffler, Sink.head[Seq[Int]])((_, _, _, fut) ⇒ fut) { implicit b ⇒
        (s1, s2, s3, sink) ⇒
          val merge = b.add(Merge[Int](2))

          Source(List(1, 2, 3)) ~> s1.in1
          Source(List(10, 11, 12)) ~> s1.in2

          s1.out1 ~> s2.in1
          s1.out2 ~> s2.in2

          s2.out1 ~> s3.in1
          s2.out2 ~> s3.in2

          s3.out1 ~> merge.in(0)
          s3.out2 ~> merge.in(1)

          merge.out.grouped(1000) ~> sink
      }.run()

      val result = Await.result(f, 3.seconds)

      result.toSet should ===(Set(4, 5, 6, 13, 14, 15))
    }

  }

}
