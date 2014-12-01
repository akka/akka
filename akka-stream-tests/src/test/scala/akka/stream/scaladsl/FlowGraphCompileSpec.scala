/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.scaladsl.OperationAttributes._
import akka.stream.FlowMaterializer
import akka.stream.OverflowStrategy
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit.{ PublisherProbe, SubscriberProbe }
import akka.stream.stage._

object FlowGraphCompileSpec {
  class Fruit
  class Apple extends Fruit
}

class FlowGraphCompileSpec extends AkkaSpec {
  import FlowGraphCompileSpec._

  implicit val mat = FlowMaterializer()

  def op[In, Out]: () ⇒ PushStage[In, Out] = { () ⇒
    new PushStage[In, Out] {
      override def onPush(elem: In, ctx: Context[Out]): Directive =
        ctx.push(elem.asInstanceOf[Out])
    }
  }

  val apples = () ⇒ Iterator.continually(new Apple)

  val f1 = Flow[String].section(name("f1"))(_.transform(op[String, String]))
  val f2 = Flow[String].section(name("f2"))(_.transform(op[String, String]))
  val f3 = Flow[String].section(name("f3"))(_.transform(op[String, String]))
  val f4 = Flow[String].section(name("f4"))(_.transform(op[String, String]))
  val f5 = Flow[String].section(name("f5"))(_.transform(op[String, String]))
  val f6 = Flow[String].section(name("f6"))(_.transform(op[String, String]))

  val in1 = Source(List("a", "b", "c"))
  val in2 = Source(List("d", "e", "f"))
  val out1 = Sink.publisher[String]
  val out2 = Sink.head[String]

  "FlowGraph" should {
    "build simple merge" in {
      FlowGraph { b ⇒
        val merge = Merge[String]
        b.
          addEdge(in1, f1, merge).
          addEdge(in2, f2, merge).
          addEdge(merge, f3, out1)
      }.run()
    }

    "build simple broadcast" in {
      FlowGraph { b ⇒
        val bcast = Broadcast[String]
        b.
          addEdge(in1, f1, bcast).
          addEdge(bcast, f2, out1).
          addEdge(bcast, f3, out2)
      }.run()
    }

    "build simple balance" in {
      FlowGraph { b ⇒
        val balance = Balance[String]
        b.
          addEdge(in1, f1, balance).
          addEdge(balance, f2, out1).
          addEdge(balance, f3, out2)
      }
    }

    "build simple merge - broadcast" in {
      FlowGraph { b ⇒
        val merge = Merge[String]
        val bcast = Broadcast[String]
        b.
          addEdge(in1, f1, merge).
          addEdge(in2, f2, merge).
          addEdge(merge, f3, bcast).
          addEdge(bcast, f4, out1).
          addEdge(bcast, f5, out2)
      }.run()
    }

    "build simple merge - broadcast with implicits" in {
      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[String]
        val bcast = Broadcast[String]
        in1 ~> f1 ~> merge ~> f2 ~> bcast ~> f3 ~> out1
        in2 ~> f4 ~> merge
        bcast ~> f5 ~> out2
      }.run()
    }

    /**
     * in ---> f1 -+-> f2 -+-> f3 ---> out1
     *             ^       |
     *             |       V
     *             f5 <-+- f4
     *                  |
     *                  V
     *                  f6 ---> out2
     */
    "detect cycle in " in {
      intercept[IllegalArgumentException] {
        FlowGraph { b ⇒
          val merge = Merge[String]
          val bcast1 = Broadcast[String]
          val bcast2 = Broadcast[String]
          val feedbackLoopBuffer = Flow[String].buffer(10, OverflowStrategy.dropBuffer)
          b.
            addEdge(in1, f1, merge).
            addEdge(merge, f2, bcast1).
            addEdge(bcast1, f3, out1).
            addEdge(bcast1, feedbackLoopBuffer, bcast2).
            addEdge(bcast2, f5, merge). // cycle
            addEdge(bcast2, f6, out2)
        }
      }.getMessage.toLowerCase should include("cycle")

    }

    "express complex topologies in a readable way" in {
      FlowGraph { implicit b ⇒
        b.allowCycles()
        val merge = Merge[String]
        val bcast1 = Broadcast[String]
        val bcast2 = Broadcast[String]
        val feedbackLoopBuffer = Flow[String].buffer(10, OverflowStrategy.dropBuffer)
        import FlowGraphImplicits._
        in1 ~> f1 ~> merge ~> f2 ~> bcast1 ~> f3 ~> out1
        bcast1 ~> feedbackLoopBuffer ~> bcast2 ~> f5 ~> merge
        bcast2 ~> f6 ~> out2
      }.run()
    }

    "build broadcast - merge" in {
      FlowGraph { implicit b ⇒
        val bcast = Broadcast[String]
        val bcast2 = Broadcast[String]
        val merge = Merge[String]
        import FlowGraphImplicits._
        in1 ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out1
        bcast ~> f4 ~> merge
      }.run()
    }

    "build wikipedia Topological_sorting" in {
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      FlowGraph { implicit b ⇒
        val b3 = Broadcast[String]
        val b7 = Broadcast[String]
        val b11 = Broadcast[String]
        val m8 = Merge[String]
        val m9 = Merge[String]
        val m10 = Merge[String]
        val m11 = Merge[String]
        val in3 = Source(List("b"))
        val in5 = Source(List("b"))
        val in7 = Source(List("a"))
        val out2 = Sink.publisher[String]
        val out9 = Sink.publisher[String]
        val out10 = Sink.publisher[String]
        def f(s: String) = Flow[String].section(name(s))(_.transform(op[String, String]))
        import FlowGraphImplicits._

        in7 ~> f("a") ~> b7 ~> f("b") ~> m11 ~> f("c") ~> b11 ~> f("d") ~> out2
        b11 ~> f("e") ~> m9 ~> f("f") ~> out9
        b7 ~> f("g") ~> m8 ~> f("h") ~> m9
        b11 ~> f("i") ~> m10 ~> f("j") ~> out10
        in5 ~> f("k") ~> m11
        in3 ~> f("l") ~> b3 ~> f("m") ~> m8
        b3 ~> f("n") ~> m10
      }.run()
    }

    "attachSource and attachSink" in {
      val mg = FlowGraph { b ⇒
        val merge = Merge[String]
        val undefinedSource1 = UndefinedSource[String]
        val undefinedSource2 = UndefinedSource[String]
        val undefinedSink1 = UndefinedSink[String]
        b.
          addEdge(undefinedSource1, f1, merge).
          addEdge(UndefinedSource[String]("src2"), f2, merge).
          addEdge(merge, f3, undefinedSink1)

        b.attachSource(undefinedSource1, in1)
        b.attachSource(UndefinedSource[String]("src2"), in2)
        b.attachSink(undefinedSink1, out1)

      }.run()
      mg.get(out1) should not be (null)
    }

    "build partial flow graphs" in {
      val undefinedSource1 = UndefinedSource[String]
      val undefinedSource2 = UndefinedSource[String]
      val undefinedSink1 = UndefinedSink[String]
      val bcast = Broadcast[String]

      val partial1 = PartialFlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[String]
        undefinedSource1 ~> f1 ~> merge ~> f2 ~> bcast ~> f3 ~> undefinedSink1
        undefinedSource2 ~> f4 ~> merge
      }
      partial1.undefinedSources should be(Set(undefinedSource1, undefinedSource2))
      partial1.undefinedSinks should be(Set(undefinedSink1))

      val partial2 = PartialFlowGraph(partial1) { implicit b ⇒
        import FlowGraphImplicits._
        b.attachSource(undefinedSource1, in1)
        b.attachSource(undefinedSource2, in2)
        bcast ~> f5 ~> UndefinedSink[String]("sink2")
      }
      partial2.undefinedSources should be(Set.empty)
      partial2.undefinedSinks should be(Set(undefinedSink1, UndefinedSink[String]("sink2")))

      FlowGraph(partial2) { b ⇒
        b.attachSink(undefinedSink1, out1)
        b.attachSink(UndefinedSink[String]("sink2"), out2)
      }.run()

      FlowGraph(partial2) { b ⇒
        b.attachSink(undefinedSink1, f1.to(out1))
        b.attachSink(UndefinedSink[String]("sink2"), f2.to(out2))
      }.run()

      FlowGraph(partial1) { implicit b ⇒
        import FlowGraphImplicits._
        b.attachSink(undefinedSink1, f1.to(out1))
        b.attachSource(undefinedSource1, Source(List("a", "b", "c")).via(f1))
        b.attachSource(undefinedSource2, Source(List("d", "e", "f")).via(f2))
        bcast ~> f5 ~> out2
      }.run()
    }

    "make it optional to specify flows" in {
      FlowGraph { implicit b ⇒
        val merge = Merge[String]
        val bcast = Broadcast[String]
        import FlowGraphImplicits._
        in1 ~> merge ~> bcast ~> out1
        in2 ~> merge
        bcast ~> out2
      }.run()
    }

    "chain input and output ports" in {
      FlowGraph { implicit b ⇒
        val zip = Zip[Int, String]
        val out = Sink.publisher[(Int, String)]
        import FlowGraphImplicits._
        Source(List(1, 2, 3)) ~> zip.left ~> out
        Source(List("a", "b", "c")) ~> zip.right
      }.run()
    }

    "build unzip - zip" in {
      FlowGraph { implicit b ⇒
        val zip = Zip[Int, String]
        val unzip = Unzip[Int, String]
        val out = Sink.publisher[(Int, String)]
        import FlowGraphImplicits._
        Source(List(1 -> "a", 2 -> "b", 3 -> "c")) ~> unzip.in
        unzip.left ~> Flow[Int].map(_ * 2) ~> zip.left
        unzip.right ~> zip.right
        zip.out ~> out
      }.run()
    }

    "distinguish between input and output ports" in {
      intercept[IllegalArgumentException] {
        FlowGraph { implicit b ⇒
          val zip = Zip[Int, String]
          val unzip = Unzip[Int, String]
          val wrongOut = Sink.publisher[(Int, Int)]
          val whatever = Sink.publisher[Any]
          "Flow(List(1, 2, 3)) ~> zip.left ~> wrongOut" shouldNot compile
          """Flow(List("a", "b", "c")) ~> zip.left""" shouldNot compile
          """Flow(List("a", "b", "c")) ~> zip.out""" shouldNot compile
          "zip.left ~> zip.right" shouldNot compile
          "Flow(List(1, 2, 3)) ~> zip.left ~> wrongOut" shouldNot compile
          """Flow(List(1 -> "a", 2 -> "b", 3 -> "c")) ~> unzip.in ~> whatever""" shouldNot compile
        }
      }.getMessage should include("empty")
    }

    "check maximumInputCount" in {
      intercept[IllegalArgumentException] {
        FlowGraph { implicit b ⇒
          val bcast = Broadcast[String]
          import FlowGraphImplicits._
          in1 ~> bcast ~> out1
          in2 ~> bcast // wrong
        }
      }.getMessage should include("at most 1 incoming")
    }

    "check maximumOutputCount" in {
      intercept[IllegalArgumentException] {
        FlowGraph { implicit b ⇒
          val merge = Merge[String]
          import FlowGraphImplicits._
          in1 ~> merge ~> out1
          in2 ~> merge
          merge ~> out2 // wrong
        }
      }.getMessage should include("at most 1 outgoing")
    }

    "build with variance" in {
      val out = Sink(SubscriberProbe[Fruit]())
      FlowGraph { b ⇒
        val merge = Merge[Fruit]
        b.
          addEdge(Source[Fruit](apples), Flow[Fruit], merge).
          addEdge(Source[Apple](apples), Flow[Apple], merge).
          addEdge(merge, Flow[Fruit].map(identity), out)
      }
    }

    "build with implicits and variance" in {
      PartialFlowGraph { implicit b ⇒
        val inA = Source(PublisherProbe[Fruit]())
        val inB = Source(PublisherProbe[Apple]())
        val outA = Sink(SubscriberProbe[Fruit]())
        val outB = Sink(SubscriberProbe[Fruit]())
        val merge = Merge[Fruit]
        val unzip = Unzip[Int, String]
        val whatever = Sink.publisher[Any]
        import FlowGraphImplicits._
        Source[Fruit](apples) ~> merge
        Source[Apple](apples) ~> merge
        inA ~> merge
        inB ~> merge
        inA ~> Flow[Fruit].map(identity) ~> merge
        inB ~> Flow[Apple].map(identity) ~> merge
        UndefinedSource[Apple] ~> merge
        UndefinedSource[Apple] ~> Flow[Fruit].map(identity) ~> merge
        UndefinedSource[Apple] ~> Flow[Apple].map(identity) ~> merge
        merge ~> Flow[Fruit].map(identity) ~> outA

        Source[Apple](apples) ~> Broadcast[Apple] ~> merge
        Source[Apple](apples) ~> Broadcast[Apple] ~> outB
        Source[Apple](apples) ~> Broadcast[Apple] ~> UndefinedSink[Fruit]
        inB ~> Broadcast[Apple] ~> merge

        Source(List(1 -> "a", 2 -> "b", 3 -> "c")) ~> unzip.in
        unzip.right ~> whatever
        unzip.left ~> UndefinedSink[Any]

        "UndefinedSource[Fruit] ~> Flow[Apple].map(identity) ~> merge" shouldNot compile
        "UndefinedSource[Fruit] ~> Broadcast[Apple]" shouldNot compile
        "merge ~> Broadcast[Apple]" shouldNot compile
        "merge ~> Flow[Fruit].map(identity) ~> Broadcast[Apple]" shouldNot compile
        "inB ~> merge ~> Broadcast[Apple]" shouldNot compile
        "inA ~> Broadcast[Apple]" shouldNot compile
      }
    }

    "build with plain flow without junctions" in {
      FlowGraph { b ⇒
        b.addEdge(in1, f1, out1)
      }.run()
      FlowGraph { b ⇒
        b.addEdge(in1, f1, f2.to(out1))
      }.run()
      FlowGraph { b ⇒
        b.addEdge(in1.via(f1), f2, out1)
      }.run()
      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        in1 ~> f1 ~> out1
      }.run()
      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        in1 ~> out1
      }.run()
      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        in1 ~> f1.to(out1)
      }.run()
      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        in1.via(f1) ~> out1
      }.run()
      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        in1.via(f1) ~> f2.to(out1)
      }.run()
    }

    "build all combinations with implicits" when {

      "Source is connected directly" in {
        PartialFlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          Source.empty[Int] ~> Flow[Int]
          Source.empty[Int] ~> Broadcast[Int]
          Source.empty[Int] ~> Sink.ignore
          Source.empty[Int] ~> UndefinedSink[Int]
        }
      }

      "Source is connected through flow" in {
        PartialFlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          Source.empty[Int] ~> Flow[Int] ~> Flow[Int]
          Source.empty[Int] ~> Flow[Int] ~> Broadcast[Int]
          Source.empty[Int] ~> Flow[Int] ~> Sink.ignore
          Source.empty[Int] ~> Flow[Int] ~> UndefinedSink[Int]
        }
      }

      "Junction is connected directly" in {
        PartialFlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          Broadcast[Int] ~> Flow[Int]
          Broadcast[Int] ~> Broadcast[Int]
          Broadcast[Int] ~> Sink.ignore
          Broadcast[Int] ~> UndefinedSink[Int]
        }
      }

      "Junction is connected through flow" in {
        PartialFlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          Broadcast[Int] ~> Flow[Int] ~> Flow[Int]
          Broadcast[Int] ~> Flow[Int] ~> Broadcast[Int]
          Broadcast[Int] ~> Flow[Int] ~> Sink.ignore
          Broadcast[Int] ~> Flow[Int] ~> UndefinedSink[Int]
        }
      }

      "UndefinedSource is connected directly" in {
        PartialFlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          UndefinedSource[Int] ~> Flow[Int]
          UndefinedSource[Int] ~> Broadcast[Int]
          UndefinedSource[Int] ~> Sink.ignore
          UndefinedSource[Int] ~> UndefinedSink[Int]
        }
      }

      "UndefinedSource is connected through flow" in {
        PartialFlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          UndefinedSource[Int] ~> Flow[Int] ~> Flow[Int]
          UndefinedSource[Int] ~> Flow[Int] ~> Broadcast[Int]
          UndefinedSource[Int] ~> Flow[Int] ~> Sink.ignore
          UndefinedSource[Int] ~> Flow[Int] ~> UndefinedSink[Int]
        }
      }
    }

    "build partial with only undefined sources and sinks" in {
      PartialFlowGraph { b ⇒
        b.addEdge(UndefinedSource[String], f1, UndefinedSink[String])
      }
      PartialFlowGraph { b ⇒
        b.addEdge(UndefinedSource[String], f1, out1)
      }
      PartialFlowGraph { b ⇒
        b.addEdge(in1, f1, UndefinedSink[String])
      }
    }

    "support interconnect between two partial flow graphs" in {
      val output1 = UndefinedSink[String]
      val output2 = UndefinedSink[String]
      val partial1 = PartialFlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val bcast = Broadcast[String]
        in1 ~> bcast ~> output1
        bcast ~> output2
      }

      val input1 = UndefinedSource[String]
      val input2 = UndefinedSource[String]
      val partial2 = PartialFlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[String]
        input1 ~> merge ~> out1
        input2 ~> merge
      }

      FlowGraph { b ⇒
        b.importPartialFlowGraph(partial1)
        b.importPartialFlowGraph(partial2)
        b.connect(output1, f1, input1)
        b.connect(output2, f2, input2)
      }.run()
    }

  }
}
