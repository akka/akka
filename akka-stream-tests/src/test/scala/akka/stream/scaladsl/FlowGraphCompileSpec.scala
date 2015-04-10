/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.OperationAttributes._
import akka.stream.ActorFlowMaterializer
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

  implicit val mat = ActorFlowMaterializer()

  def op[In, Out]: () ⇒ PushStage[In, Out] = { () ⇒
    new PushStage[In, Out] {
      override def onPush(elem: In, ctx: Context[Out]): SyncDirective =
        ctx.push(elem.asInstanceOf[Out])
    }
  }

  val apples = () ⇒ Iterator.continually(new Apple)

  val f1 = Flow[String].transform(op[String, String]).named("f1")
  val f2 = Flow[String].transform(op[String, String]).named("f2")
  val f3 = Flow[String].transform(op[String, String]).named("f3")
  val f4 = Flow[String].transform(op[String, String]).named("f4")
  val f5 = Flow[String].transform(op[String, String]).named("f5")
  val f6 = Flow[String].transform(op[String, String]).named("f6")

  val in1 = Source(List("a", "b", "c"))
  val in2 = Source(List("d", "e", "f"))
  val out1 = Sink.publisher[String]
  val out2 = Sink.head[String]

  "A Graph" should {
    "build simple merge" in {
      FlowGraph.closed() { b ⇒
        val merge = b.add(Merge[String](2))
        b.addEdge(b.add(in1), f1, merge.in(0))
        b.addEdge(b.add(in2), f2, merge.in(1))
        b.addEdge(merge.out, f3, b.add(out1))
      }.run()
    }

    "build simple broadcast" in {
      FlowGraph.closed() { b ⇒
        val bcast = b.add(Broadcast[String](2))
        b.addEdge(b.add(in1), f1, bcast.in)
        b.addEdge(bcast.out(0), f2, b.add(out1))
        b.addEdge(bcast.out(1), f3, b.add(out2))
      }.run()
    }

    "build simple balance" in {
      FlowGraph.closed() { b ⇒
        val balance = b.add(Balance[String](2))
        b.addEdge(b.add(in1), f1, balance.in)
        b.addEdge(balance.out(0), f2, b.add(out1))
        b.addEdge(balance.out(1), f3, b.add(out2))
      }
    }

    "build simple merge - broadcast" in {
      FlowGraph.closed() { b ⇒
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        b.addEdge(b.add(in1), f1, merge.in(0))
        b.addEdge(b.add(in2), f2, merge.in(1))
        b.addEdge(merge.out, f3, bcast.in)
        b.addEdge(bcast.out(0), f4, b.add(out1))
        b.addEdge(bcast.out(1), f5, b.add(out2))
      }.run()
    }

    "build simple merge - broadcast with implicits" in {
      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        b.add(in1) ~> f1 ~> merge.in(0)
        merge.out ~> f2 ~> bcast.in
        bcast.out(0) ~> f3 ~> b.add(out1)
        b.add(in2) ~> f4 ~> merge.in(1)
        bcast.out(1) ~> f5 ~> b.add(out2)
      }.run()
    }

    /*
     * in ---> f1 -+-> f2 -+-> f3 ---> b.add(out1)
     *             ^       |
     *             |       V
     *             f5 <-+- f4
     *                  |
     *                  V
     *                  f6 ---> b.add(out2)
     */
    "detect cycle in " in {
      pending // FIXME needs cycle detection capability
      intercept[IllegalArgumentException] {
        FlowGraph.closed() { b ⇒
          val merge = b.add(Merge[String](2))
          val bcast1 = b.add(Broadcast[String](2))
          val bcast2 = b.add(Broadcast[String](2))
          val feedbackLoopBuffer = Flow[String].buffer(10, OverflowStrategy.dropBuffer)
          b.addEdge(b.add(in1), f1, merge.in(0))
          b.addEdge(merge.out, f2, bcast1.in)
          b.addEdge(bcast1.out(0), f3, b.add(out1))
          b.addEdge(bcast1.out(1), feedbackLoopBuffer, bcast2.in)
          b.addEdge(bcast2.out(0), f5, merge.in(1)) // cycle
          b.addEdge(bcast2.out(1), f6, b.add(out2))
        }
      }.getMessage.toLowerCase should include("cycle")

    }

    "express complex topologies in a readable way" in {
      FlowGraph.closed() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast1 = b.add(Broadcast[String](2))
        val bcast2 = b.add(Broadcast[String](2))
        val feedbackLoopBuffer = Flow[String].buffer(10, OverflowStrategy.dropBuffer)
        import FlowGraph.Implicits._
        b.add(in1) ~> f1 ~> merge ~> f2 ~> bcast1 ~> f3 ~> b.add(out1)
        bcast1 ~> feedbackLoopBuffer ~> bcast2 ~> f5 ~> merge
        bcast2 ~> f6 ~> b.add(out2)
      }.run()
    }

    "build broadcast - merge" in {
      FlowGraph.closed() { implicit b ⇒
        val bcast = b.add(Broadcast[String](2))
        val merge = b.add(Merge[String](2))
        import FlowGraph.Implicits._
        in1 ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out1
        bcast ~> f4 ~> merge
      }.run()
    }

    "build wikipedia Topological_sorting" in {
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      FlowGraph.closed() { implicit b ⇒
        val b3 = b.add(Broadcast[String](2))
        val b7 = b.add(Broadcast[String](2))
        val b11 = b.add(Broadcast[String](3))
        val m8 = b.add(Merge[String](2))
        val m9 = b.add(Merge[String](2))
        val m10 = b.add(Merge[String](2))
        val m11 = b.add(Merge[String](2))
        val in3 = Source(List("b"))
        val in5 = Source(List("b"))
        val in7 = Source(List("a"))
        val out2 = Sink.publisher[String]
        val out9 = Sink.publisher[String]
        val out10 = Sink.publisher[String]
        def f(s: String) = Flow[String].transform(op[String, String]).named(s)
        import FlowGraph.Implicits._

        in7 ~> f("a") ~> b7 ~> f("b") ~> m11 ~> f("c") ~> b11 ~> f("d") ~> out2
        b11 ~> f("e") ~> m9 ~> f("f") ~> out9
        b7 ~> f("g") ~> m8 ~> f("h") ~> m9
        b11 ~> f("i") ~> m10 ~> f("j") ~> out10
        in5 ~> f("k") ~> m11
        in3 ~> f("l") ~> b3 ~> f("m") ~> m8
        b3 ~> f("n") ~> m10
      }.run()
    }

    "make it optional to specify flows" in {
      FlowGraph.closed() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        import FlowGraph.Implicits._
        in1 ~> merge ~> bcast ~> out1
        in2 ~> merge
        bcast ~> out2
      }.run()
    }

    "build unzip - zip" in {
      FlowGraph.closed() { implicit b ⇒
        val zip = b.add(Zip[Int, String]())
        val unzip = b.add(Unzip[Int, String]())
        val out = Sink.publisher[(Int, String)]
        import FlowGraph.Implicits._
        Source(List(1 -> "a", 2 -> "b", 3 -> "c")) ~> unzip.in
        unzip.out0 ~> Flow[Int].map(_ * 2) ~> zip.in0
        unzip.out1 ~> zip.in1
        zip.out ~> out
      }.run()
    }

    "distinguish between input and output ports" in {
      intercept[IllegalArgumentException] {
        FlowGraph.closed() { implicit b ⇒
          val zip = b.add(Zip[Int, String]())
          val unzip = b.add(Unzip[Int, String]())
          val wrongOut = Sink.publisher[(Int, Int)]
          val whatever = Sink.publisher[Any]
          "Flow(List(1, 2, 3)) ~> zip.left ~> wrongOut" shouldNot compile
          """Flow(List("a", "b", "c")) ~> zip.left""" shouldNot compile
          """Flow(List("a", "b", "c")) ~> zip.out""" shouldNot compile
          "zip.left ~> zip.right" shouldNot compile
          "Flow(List(1, 2, 3)) ~> zip.left ~> wrongOut" shouldNot compile
          """Flow(List(1 -> "a", 2 -> "b", 3 -> "c")) ~> unzip.in ~> whatever""" shouldNot compile
        }
      }.getMessage should include("unconnected")
    }

    "build with variance" in {
      val out = Sink(SubscriberProbe[Fruit]())
      FlowGraph.closed() { b ⇒
        val merge = b.add(Merge[Fruit](2))
        b.addEdge(b add Source[Fruit](apples), Flow[Fruit], merge.in(0))
        b.addEdge(b add Source[Apple](apples), Flow[Apple], merge.in(1))
        b.addEdge(merge.out, Flow[Fruit].map(identity), b add out)
      }
    }

    "build with implicits and variance" in {
      FlowGraph.closed() { implicit b ⇒
        def appleSource = b.add(Source(PublisherProbe[Apple]))
        def fruitSource = b.add(Source(PublisherProbe[Fruit]))
        val outA = b add Sink(SubscriberProbe[Fruit]())
        val outB = b add Sink(SubscriberProbe[Fruit]())
        val merge = b add Merge[Fruit](11)
        val unzip = b add Unzip[Int, String]()
        val whatever = b add Sink.publisher[Any]
        import FlowGraph.Implicits._
        b.add(Source[Fruit](apples)) ~> merge.in(0)
        appleSource ~> merge.in(1)
        appleSource ~> merge.in(2)
        fruitSource ~> merge.in(3)
        fruitSource ~> Flow[Fruit].map(identity) ~> merge.in(4)
        appleSource ~> Flow[Apple].map(identity) ~> merge.in(5)
        b.add(Source(apples)) ~> merge.in(6)
        b.add(Source(apples)) ~> Flow[Fruit].map(identity) ~> merge.in(7)
        b.add(Source(apples)) ~> Flow[Apple].map(identity) ~> merge.in(8)
        merge.out ~> Flow[Fruit].map(identity) ~> outA

        b.add(Source(apples)) ~> Flow[Apple] ~> merge.in(9)
        b.add(Source(apples)) ~> Flow[Apple] ~> outB
        b.add(Source(apples)) ~> Flow[Apple] ~> b.add(Sink.publisher[Fruit])
        appleSource ~> Flow[Apple] ~> merge.in(10)

        Source(List(1 -> "a", 2 -> "b", 3 -> "c")) ~> unzip.in
        unzip.out1 ~> whatever
        unzip.out0 ~> b.add(Sink.publisher[Any])

        "merge.out ~> b.add(Broadcast[Apple](2))" shouldNot compile
        "merge.out ~> Flow[Fruit].map(identity) ~> b.add(Broadcast[Apple](2))" shouldNot compile
        "fruitSource ~> merge ~> b.add(Broadcast[Apple](2))" shouldNot compile
      }
    }

    "build with plain flow without junctions" in {
      FlowGraph.closed() { b ⇒
        b.addEdge(b.add(in1), f1, b.add(out1))
      }.run()
      FlowGraph.closed() { b ⇒
        b.addEdge(b.add(in1), f1, b.add(f2.to(out1)))
      }.run()
      FlowGraph.closed() { b ⇒
        b.addEdge(b.add(in1 via f1), f2, b.add(out1))
      }.run()
      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        b.add(in1) ~> f1 ~> b.add(out1)
      }.run()
      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        b.add(in1) ~> b.add(out1)
      }.run()
      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        b.add(in1) ~> b.add(f1 to out1)
      }.run()
      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        b.add(in1 via f1) ~> b.add(out1)
      }.run()
      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        b.add(in1 via f1) ~> b.add(f2 to out1)
      }.run()
    }

  }
}
