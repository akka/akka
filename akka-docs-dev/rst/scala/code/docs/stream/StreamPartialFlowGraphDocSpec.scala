/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.scaladsl.PartialFlowGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.UndefinedSink
import akka.stream.scaladsl.UndefinedSource
import akka.stream.scaladsl.Zip
import akka.stream.scaladsl.ZipWith
import akka.stream.scaladsl.Merge
import akka.stream.scaladsl.Ports
import akka.stream.testkit.AkkaSpec

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

class StreamPartialFlowGraphDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val mat = FlowMaterializer()

  "build with open ports" in {
    // format: OFF
    //#simple-partial-flow-graph
    // defined outside as they will be used by different FlowGraphs
    // 1) first by the PartialFlowGraph to mark its open input and output ports
    // 2) then by the assembling FlowGraph which will attach real sinks and sources to them
    val in1 = UndefinedSource[Int]
    val in2 = UndefinedSource[Int]
    val in3 = UndefinedSource[Int]
    val out = UndefinedSink[Int]

    val pickMaxOfThree: PartialFlowGraph = PartialFlowGraph { implicit b =>
      import FlowGraphImplicits._

      val zip1 = ZipWith[Int, Int, Int](math.max _)
      val zip2 = ZipWith[Int, Int, Int](math.max _)

      in1 ~> zip1.left
      in2 ~> zip1.right
             zip1.out ~> zip2.left
                  in3 ~> zip2.right
                         zip2.out ~> out
    }
    //#simple-partial-flow-graph
    // format: ON
    //#simple-partial-flow-graph

    val resultSink = Sink.head[Int]

    val g = FlowGraph { b =>
      // import the partial flow graph explicitly
      b.importPartialFlowGraph(pickMaxOfThree)

      b.attachSource(in1, Source.single(1))
      b.attachSource(in2, Source.single(2))
      b.attachSource(in3, Source.single(3))
      b.attachSink(out, resultSink)
    }

    val materialized = g.run()
    val max: Future[Int] = materialized.get(resultSink)
    Await.result(max, 300.millis) should equal(3)
    //#simple-partial-flow-graph

    val g2 =
      //#simple-partial-flow-graph-import-shorthand
      FlowGraph(pickMaxOfThree) { b =>
        b.attachSource(in1, Source.single(1))
        b.attachSource(in2, Source.single(2))
        b.attachSource(in3, Source.single(3))
        b.attachSink(out, resultSink)
      }
    //#simple-partial-flow-graph-import-shorthand
    val materialized2 = g.run()
    val max2: Future[Int] = materialized2.get(resultSink)
    Await.result(max2, 300.millis) should equal(3)
  }

  "build source from partial flow graph" in {
    //#source-from-partial-flow-graph
    val pairs: Source[(Int, Int)] = Source() { implicit b =>
      import FlowGraphImplicits._

      // prepare graph elements
      val undefinedSink = UndefinedSink[(Int, Int)]
      val zip = Zip[Int, Int]
      def ints = Source(() => Iterator.from(1))

      // connect the graph
      ints ~> Flow[Int].filter(_ % 2 != 0) ~> zip.left
      ints ~> Flow[Int].filter(_ % 2 == 0) ~> zip.right
      zip.out ~> undefinedSink

      // expose undefined sink
      undefinedSink
    }

    val firstPair: Future[(Int, Int)] = pairs.runWith(Sink.head)
    //#source-from-partial-flow-graph
    Await.result(firstPair, 300.millis) should equal(1 -> 2)
  }

  "build flow from partial flow graph" in {
    //#flow-from-partial-flow-graph
    val pairUpWithToString = Flow() { implicit b =>
      import FlowGraphImplicits._

      // prepare graph elements
      val undefinedSource = UndefinedSource[Int]
      val undefinedSink = UndefinedSink[(Int, String)]

      val broadcast = Broadcast[Int]
      val zip = Zip[Int, String]

      // connect the graph
      undefinedSource ~> broadcast
      broadcast ~> Flow[Int].map(identity) ~> zip.left
      broadcast ~> Flow[Int].map(_.toString) ~> zip.right
      zip.out ~> undefinedSink

      // expose undefined ports
      (undefinedSource, undefinedSink)
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

  "help users in providing complex ports" in {
    object AudioPorts extends Ports {
      val nums = Input[Int] // TODO: use I / O words or keep sinks/sources?
      val words = Input[String]

      val out = Output[String]
    }

    // building
    val g = PartialFlowGraph(AudioPorts) { implicit b ⇒
      import FlowGraphImplicits._
      val merge = Merge[String]
      // format: OFF
      AudioPorts.nums ~> Flow[Int].map(_.toString) ~> merge
                                  AudioPorts.words ~> merge ~> AudioPorts.out
      // format: ON
    }

    // connecting, current style:
    FlowGraph(g) { b ⇒
      b.attachSource(AudioPorts.nums, Source(1 to 10))
      b.attachSource(AudioPorts.words, Source((1 to 10).map(_.toString)))
      b.attachSink(AudioPorts.out, Sink.ignore)
    }.run()

    // connecting, proposed style for Ports:
    FlowGraph(g) { implicit b ⇒
      import AudioPorts._

      AudioPorts.nums(Source(1 to 10))( /* implicit */ b)
      AudioPorts.words(Source((1 to 10).map(_.toString)))
      AudioPorts.out(Sink.ignore)
    }.run()
  }

  "throw when not all Ports ports are connected" in {
    case object AudioPorts extends Ports {
      val nums = Input[Int]
      val words = Input[String]

      val out = Output[String]
    }

    val ex = intercept[IllegalArgumentException] {
      PartialFlowGraph(AudioPorts) { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[String]
        // format: OFF
        AudioPorts.nums ~> Flow[Int].map(_.toString) ~> merge
                                    AudioPorts.words ~> merge ~> Sink.ignore
        // format: ON
      }
    }

    ex.getMessage should include("must contain all ports")

  }
}
