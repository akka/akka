/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.scaladsl._
import akka.stream._
import akka.stream.testkit.AkkaSpec

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

class StreamPartialFlowGraphDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val mat = ActorMaterializer()

  "build with open ports" in {
    //#simple-partial-flow-graph
    val pickMaxOfThree = FlowGraph.partial() { implicit b =>
      import FlowGraph.Implicits._

      val zip1 = b.add(ZipWith[Int, Int, Int](math.max _))
      val zip2 = b.add(ZipWith[Int, Int, Int](math.max _))
      zip1.out ~> zip2.in0

      UniformFanInShape(zip2.out, zip1.in0, zip1.in1, zip2.in1)
    }

    val resultSink = Sink.head[Int]

    val g = FlowGraph.closed(resultSink) { implicit b =>
      sink =>
        import FlowGraph.Implicits._

        // importing the partial graph will return its shape (inlets & outlets)
        val pm3 = b.add(pickMaxOfThree)

        Source.single(1) ~> pm3.in(0)
        Source.single(2) ~> pm3.in(1)
        Source.single(3) ~> pm3.in(2)
        pm3.out ~> sink.inlet
    }

    val max: Future[Int] = g.run()
    Await.result(max, 300.millis) should equal(3)
    //#simple-partial-flow-graph
  }

  "build source from partial flow graph" in {
    //#source-from-partial-flow-graph
    val pairs = Source() { implicit b =>
      import FlowGraph.Implicits._

      // prepare graph elements
      val zip = b.add(Zip[Int, Int]())
      def ints = Source(() => Iterator.from(1))

      // connect the graph
      ints.filter(_ % 2 != 0) ~> zip.in0
      ints.filter(_ % 2 == 0) ~> zip.in1

      // expose port
      zip.out
    }

    val firstPair: Future[(Int, Int)] = pairs.runWith(Sink.head)
    //#source-from-partial-flow-graph
    Await.result(firstPair, 300.millis) should equal(1 -> 2)
  }

  "build flow from partial flow graph" in {
    //#flow-from-partial-flow-graph
    val pairUpWithToString = Flow() { implicit b =>
      import FlowGraph.Implicits._

      // prepare graph elements
      val broadcast = b.add(Broadcast[Int](2))
      val zip = b.add(Zip[Int, String]())

      // connect the graph
      broadcast.out(0).map(identity) ~> zip.in0
      broadcast.out(1).map(_.toString) ~> zip.in1

      // expose ports
      (broadcast.in, zip.out)
    }

    //#flow-from-partial-flow-graph

    // format: OFF
    val (_, matSink: Future[(Int, String)]) =
      //#flow-from-partial-flow-graph
    pairUpWithToString.runWith(Source(List(1)), Sink.head)
    //#flow-from-partial-flow-graph
    // format: ON

    Await.result(matSink, 300.millis) should equal(1 -> "1")
  }
}
