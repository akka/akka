/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.Merge
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Zip
import akka.stream.testkit.AkkaSpec

import scala.concurrent.Await
import scala.concurrent.duration._

class FlowGraphDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val mat = ActorFlowMaterializer()

  "build simple graph" in {
    //format: OFF
    //#simple-flow-graph
    val g = FlowGraph.closed() { implicit b =>
      import FlowGraph.Implicits._
      val in = Source(1 to 10)
      val out = Sink.ignore

      val bcast = b.add(Broadcast[Int](2))
      val merge = b.add(Merge[Int](2))

      val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

      in ~> f1 ~> bcast.in
                  bcast.out(0) ~> f2 ~> merge.in(0)
                  bcast.out(1) ~> f4 ~> merge.in(1)
                                        merge.out ~> f3 ~> out
    }
    //#simple-flow-graph
    //format: ON

    //#simple-graph-run
    g.run()
    //#simple-graph-run
  }

  "build simple graph without implicits" in {
    //#simple-flow-graph-no-implicits
    val g = FlowGraph.closed() { b =>
      val in = Source(1 to 10)
      val out = Sink.ignore

      val broadcast = b.add(Broadcast[Int](2))
      val merge = b.add(Merge[Int](2))

      val f1 = Flow[Int].map(_ + 10)
      val f3 = Flow[Int].map(_.toString)
      val f2 = Flow[Int].map(_ + 20)

      b.addEdge(b.add(in), broadcast.in)
      b.addEdge(broadcast.out(0), f1, merge.in(0))
      b.addEdge(broadcast.out(1), f2, merge.in(1))
      b.addEdge(merge.out, f3, b.add(out))
    }
    //#simple-flow-graph-no-implicits

    g.run()
  }

  "flow connection errors" in {
    intercept[IllegalArgumentException] {
      //#simple-graph
      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._
        val source1 = Source(1 to 10)
        val source2 = Source(1 to 10)

        val zip = b.add(Zip[Int, Int]())

        source1 ~> zip.in0
        source2 ~> zip.in1
        // unconnected zip.out (!) => "must have at least 1 outgoing edge"
      }
      //#simple-graph
    }.getMessage should include("unconnected ports: Zip.out")
  }

  "reusing a flow in a graph" in {
    //#flow-graph-reusing-a-flow

    val topHeadSink = Sink.head[Int]
    val bottomHeadSink = Sink.head[Int]
    val sharedDoubler = Flow[Int].map(_ * 2)

    //#flow-graph-reusing-a-flow

    // format: OFF
    val g =
    //#flow-graph-reusing-a-flow
    FlowGraph.closed(topHeadSink, bottomHeadSink)((_, _)) { implicit b =>
      (topHS, bottomHS) =>
      import FlowGraph.Implicits._
      val broadcast = b.add(Broadcast[Int](2))
      Source.single(1) ~> broadcast.in

      broadcast.out(0) ~> sharedDoubler ~> topHS.inlet
      broadcast.out(1) ~> sharedDoubler ~> bottomHS.inlet
    }
    //#flow-graph-reusing-a-flow
    // format: ON
    val (topFuture, bottomFuture) = g.run()
    Await.result(topFuture, 300.millis) shouldEqual 2
    Await.result(bottomFuture, 300.millis) shouldEqual 2
  }

}
