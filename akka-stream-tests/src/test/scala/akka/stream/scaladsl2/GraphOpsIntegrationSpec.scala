package akka.stream.scaladsl2

import akka.stream.MaterializerSettings
import akka.stream.scaladsl2.FlowGraphImplicits._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit.{ OnNext, SubscriberProbe }
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

object GraphOpsIntegrationSpec {

  object Lego {
    def apply(pipeline: Flow[String, String]): Lego = {
      val in = UndefinedSource[String]
      val out = UndefinedSink[ByteString]
      val graph = PartialFlowGraph { implicit builder ⇒
        val balance = Balance[String]
        val merge = Merge[String]
        in ~> Flow[String].map(_.trim) ~> balance
        balance ~> pipeline ~> merge
        balance ~> pipeline ~> merge
        balance ~> pipeline ~> merge
        merge ~> Flow[String].map(_.trim).map(ByteString.fromString) ~> out
      }
      new Lego(in, out, graph)
    }
  }

  class Lego private (
    private val in: UndefinedSource[String],
    private val out: UndefinedSink[ByteString],
    private val graph: PartialFlowGraph) {

    def connect(that: Lego, adapter: Flow[ByteString, String]): Lego = {
      val newGraph = PartialFlowGraph { builder ⇒
        builder.importPartialFlowGraph(this.graph)
        builder.importPartialFlowGraph(that.graph)
        builder.connect(this.out, adapter, that.in)
      }
      new Lego(this.in, that.out, newGraph)
    }

    def run(source: Source[String], sink: Sink[ByteString])(implicit materializer: FlowMaterializer): Unit =
      FlowGraph(graph) { builder ⇒
        builder.attachSource(in, source)
        builder.attachSink(out, sink)
      }.run()

  }
}

class GraphOpsIntegrationSpec extends AkkaSpec {
  import akka.stream.scaladsl2.GraphOpsIntegrationSpec._

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "FlowGraphs" must {

    "support broadcast - merge layouts" in {
      val resultFuture = Sink.future[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        val merge = Merge[Int]("merge")

        Source(List(1, 2, 3)) ~> bcast
        bcast ~> merge
        bcast ~> Flow[Int].map(_ + 3) ~> merge
        merge ~> Flow[Int].grouped(10) ~> resultFuture
      }.run()

      Await.result(g.get(resultFuture), 3.seconds).sorted should be(List(1, 2, 3, 4, 5, 6))
    }

    "support balance - merge (parallelization) layouts" in {
      val elements = 0 to 10
      val in = Source(elements)
      val f = Flow[Int]
      val out = Sink.future[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val balance = Balance[Int]
        val merge = Merge[Int]

        in ~> balance ~> f ~> merge
        balance ~> f ~> merge
        balance ~> f ~> merge
        balance ~> f ~> merge
        balance ~> f ~> merge ~> Flow[Int].grouped(elements.size * 2) ~> out
      }.run()

      Await.result(g.get(out), 3.seconds).sorted should be(elements)
    }

    "support wikipedia Topological_sorting 2" in {
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      val resultFuture2 = Sink.future[Seq[Int]]
      val resultFuture9 = Sink.future[Seq[Int]]
      val resultFuture10 = Sink.future[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val b3 = Broadcast[Int]("b3")
        val b7 = Broadcast[Int]("b7")
        val b11 = Broadcast[Int]("b11")
        val m8 = Merge[Int]("m8")
        val m9 = Merge[Int]("m9")
        val m10 = Merge[Int]("m10")
        val m11 = Merge[Int]("m11")
        val in3 = Source(List(3))
        val in5 = Source(List(5))
        val in7 = Source(List(7))

        // First layer
        in7 ~> b7
        b7 ~> m11
        b7 ~> m8

        in5 ~> m11

        in3 ~> b3
        b3 ~> m8
        b3 ~> m10

        // Second layer
        m11 ~> b11
        b11 ~> Flow[Int].grouped(1000) ~> resultFuture2 // Vertex 2 is omitted since it has only one in and out
        b11 ~> m9
        b11 ~> m10

        m8 ~> m9

        // Third layer
        m9 ~> Flow[Int].grouped(1000) ~> resultFuture9
        m10 ~> Flow[Int].grouped(1000) ~> resultFuture10

      }.run()

      Await.result(g.get(resultFuture2), 3.seconds).sorted should be(List(5, 7))
      Await.result(g.get(resultFuture9), 3.seconds).sorted should be(List(3, 5, 7, 7))
      Await.result(g.get(resultFuture10), 3.seconds).sorted should be(List(3, 5, 7))

    }

    "allow adding of flows to sources and sinks to flows" in {
      val resultFuture = Sink.future[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        val merge = Merge[Int]("merge")

        Source(List(1, 2, 3)) ~> Flow[Int].map(_ * 2) ~> bcast
        bcast ~> merge
        bcast ~> Flow[Int].map(_ + 3) ~> merge
        merge ~> Flow[Int].grouped(10).connect(resultFuture)
      }.run()

      Await.result(g.get(resultFuture), 3.seconds) should contain theSameElementsAs (Seq(2, 4, 6, 5, 7, 9))
    }

    "be able to run plain flow" in {
      val p = Source(List(1, 2, 3)).runWith(Sink.publisher)
      val s = SubscriberProbe[Int]
      val flow = Flow[Int].map(_ * 2)
      FlowGraph { implicit builder ⇒
        import FlowGraphImplicits._
        Source(p) ~> flow ~> Sink(s)
      }.run()
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext(1 * 2)
      s.expectNext(2 * 2)
      s.expectNext(3 * 2)
      s.expectComplete()
    }

    "support continued transformation from undefined source/sink" in {
      val input1 = UndefinedSource[Int]
      val output1 = UndefinedSink[Int]
      val output2 = UndefinedSink[String]
      val partial = PartialFlowGraph { implicit builder ⇒
        val bcast = Broadcast[String]("bcast")
        input1 ~> Flow[Int].map(_.toString) ~> bcast ~> Flow[String].map(_.toInt) ~> output1
        bcast ~> Flow[String].map("elem-" + _) ~> output2
      }

      val s1 = SubscriberProbe[Int]
      val s2 = SubscriberProbe[String]
      FlowGraph(partial) { builder ⇒
        builder.attachSource(input1, Source(List(0, 1, 2).map(_ + 1)))
        builder.attachSink(output1, Flow[Int].filter(n ⇒ (n % 2) != 0).connect(Sink(s1)))
        builder.attachSink(output2, Flow[String].map(_.toUpperCase).connect(Sink(s2)))
      }.run()

      val sub1 = s1.expectSubscription()
      val sub2 = s2.expectSubscription()
      sub1.request(10)
      sub2.request(10)
      s1.expectNext(1)
      s1.expectNext(3)
      s1.expectComplete()
      s2.expectNext("ELEM-1")
      s2.expectNext("ELEM-2")
      s2.expectNext("ELEM-3")
      s2.expectComplete()
    }

    "be possible to use as lego bricks" in {
      val lego1 = Lego(Flow[String].filter(_.length > 3).map(s ⇒ s" $s "))
      val lego2 = Lego(Flow[String].map(_.toUpperCase))
      val lego3 = lego1.connect(lego2, Flow[ByteString].map(_.utf8String))
      val source = Source(List("green ", "blue", "red", "yellow", "black"))
      val s = SubscriberProbe[ByteString]
      val sink = Sink(s)
      lego3.run(source, sink)
      val sub = s.expectSubscription()
      sub.request(100)
      val result = (s.probe.receiveN(4) collect {
        case OnNext(b: ByteString) ⇒ b.utf8String
      }).sorted
      result should be(Vector("BLACK", "BLUE", "GREEN", "YELLOW"))
      s.expectComplete()
    }

  }

}
