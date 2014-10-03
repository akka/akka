package akka.stream.scaladsl2

import akka.stream.testkit.AkkaSpec

import akka.stream.{ OverflowStrategy, MaterializerSettings }
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.scaladsl2.FlowGraphImplicits._

class GraphOpsIntegrationSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "FlowGraphs" must {

    "support broadcast - merge layouts" in {
      val resultFuture = FutureDrain[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        val merge = Merge[Int]("merge")

        Source(List(1, 2, 3)) ~> bcast
        bcast ~> merge
        bcast ~> Flow[Int].map(_ + 3) ~> merge
        merge ~> Flow[Int].grouped(10) ~> resultFuture
      }.run()

      Await.result(g.getDrainFor(resultFuture), 3.seconds).sorted should be(List(1, 2, 3, 4, 5, 6))
    }

    "support wikipedia Topological_sorting 2" in {
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      val resultFuture2 = FutureDrain[Seq[Int]]
      val resultFuture9 = FutureDrain[Seq[Int]]
      val resultFuture10 = FutureDrain[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val b3 = Broadcast[Int]("b3")
        val b7 = Broadcast[Int]("b7")
        val b11 = Broadcast[Int]("b11")
        val m8 = Merge[Int]("m8")
        val m9 = Merge[Int]("m9")
        val m10 = Merge[Int]("m10")
        val m11 = Merge[Int]("m11")
        val in3 = IterableTap(List(3))
        val in5 = IterableTap(List(5))
        val in7 = IterableTap(List(7))

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

      Await.result(g.getDrainFor(resultFuture2), 3.seconds).sorted should be(List(5, 7))
      Await.result(g.getDrainFor(resultFuture9), 3.seconds).sorted should be(List(3, 5, 7, 7))
      Await.result(g.getDrainFor(resultFuture10), 3.seconds).sorted should be(List(3, 5, 7))

    }

    "allow adding of flows to sources and sinks to flows" in {
      val resultFuture = FutureDrain[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        val merge = Merge[Int]("merge")

        Source(List(1, 2, 3)) ~> Flow[Int].map(_ * 2) ~> bcast
        bcast ~> merge
        bcast ~> Flow[Int].map(_ + 3) ~> merge
        merge ~> Flow[Int].grouped(10).connect(resultFuture)
      }.run()

      Await.result(g.getDrainFor(resultFuture), 3.seconds) should contain theSameElementsAs (Seq(2, 4, 6, 5, 7, 9))
    }
  }

}
