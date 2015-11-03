/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.Attributes._
import akka.stream.{ ClosedShape, ActorMaterializer, OverflowStrategy }
import akka.stream.testkit._
import akka.stream.stage._

object FlowGraphCompileSpec {
  class Fruit
  class Apple extends Fruit
}

class FlowGraphCompileSpec extends AkkaSpec {
  import FlowGraphCompileSpec._

  implicit val mat = ActorMaterializer()

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
    import FlowGraph.Implicits._
    "build simple merge" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        b.add(in1) ~> b.add(f1) ~> merge.in(0)
        b.add(in2) ~> b.add(f2) ~> merge.in(1)
        merge.out ~> b.add(f3) ~> b.add(out1)
        ClosedShape
      }).run()
    }

    "build simple broadcast" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val bcast = b.add(Broadcast[String](2))
        b.add(in1) ~> b.add(f1) ~> bcast.in
        bcast.out(0) ~> b.add(f2) ~> b.add(out1)
        bcast.out(1) ~> b.add(f3) ~> b.add(out2)
        ClosedShape
      }).run()
    }

    "build simple balance" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val balance = b.add(Balance[String](2))
        b.add(in1) ~> b.add(f1) ~> balance.in
        balance.out(0) ~> b.add(f2) ~> b.add(out1)
        balance.out(1) ~> b.add(f3) ~> b.add(out2)
        ClosedShape
      })
    }

    "build simple merge - broadcast" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        b.add(in1) ~> b.add(f1) ~> merge.in(0)
        b.add(in2) ~> b.add(f2) ~> merge.in(1)
        merge ~> b.add(f3) ~> bcast
        bcast.out(0) ~> b.add(f4) ~> b.add(out1)
        bcast.out(1) ~> b.add(f5) ~> b.add(out2)
        ClosedShape
      }).run()
    }

    "build simple merge - broadcast with implicits" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        import FlowGraph.Implicits._
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        b.add(in1) ~> b.add(f1) ~> merge.in(0)
        merge.out ~> b.add(f2) ~> bcast.in
        bcast.out(0) ~> b.add(f3) ~> b.add(out1)
        b.add(in2) ~> b.add(f4) ~> merge.in(1)
        bcast.out(1) ~> b.add(f5) ~> b.add(out2)
        ClosedShape
      }).run()
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
        RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
          val merge = b.add(Merge[String](2))
          val bcast1 = b.add(Broadcast[String](2))
          val bcast2 = b.add(Broadcast[String](2))
          val feedbackLoopBuffer = b.add(Flow[String].buffer(10, OverflowStrategy.dropBuffer))
          b.add(in1) ~> b.add(f1) ~> merge.in(0)
          merge ~> b.add(f2) ~> bcast1
          bcast1.out(0) ~> b.add(f3) ~> b.add(out1)
          bcast1.out(1) ~> feedbackLoopBuffer ~> bcast2
          bcast2.out(0) ~> b.add(f5) ~> merge.in(1) // cycle
          bcast2.out(1) ~> b.add(f6) ~> b.add(out2)
          ClosedShape
        })
      }.getMessage.toLowerCase should include("cycle")

    }

    "express complex topologies in a readable way" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast1 = b.add(Broadcast[String](2))
        val bcast2 = b.add(Broadcast[String](2))
        val feedbackLoopBuffer = b.add(Flow[String].buffer(10, OverflowStrategy.dropBuffer))
        import FlowGraph.Implicits._
        b.add(in1) ~> b.add(f1) ~> merge ~> b.add(f2) ~> bcast1 ~> b.add(f3) ~> b.add(out1)
        bcast1 ~> feedbackLoopBuffer ~> bcast2 ~> b.add(f5) ~> merge
        bcast2 ~> b.add(f6) ~> b.add(out2)
        ClosedShape
      }).run()
    }

    "build broadcast - merge" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val bcast = b.add(Broadcast[String](2))
        val merge = b.add(Merge[String](2))
        import FlowGraph.Implicits._
        b.add(in1) ~> b.add(f1) ~> bcast ~> b.add(f2) ~> merge ~> b.add(f3) ~> b.add(out1)
        bcast ~> b.add(f4) ~> merge
        ClosedShape
      }).run()
    }

    "build wikipedia Topological_sorting" in {
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val b3 = b.add(Broadcast[String](2))
        val b7 = b.add(Broadcast[String](2))
        val b11 = b.add(Broadcast[String](3))
        val m8 = b.add(Merge[String](2))
        val m9 = b.add(Merge[String](2))
        val m10 = b.add(Merge[String](2))
        val m11 = b.add(Merge[String](2))
        val in3 = b.add(Source(List("b")))
        val in5 = b.add(Source(List("b")))
        val in7 = b.add(Source(List("a")))
        val out2 = b.add(Sink.publisher[String])
        val out9 = b.add(Sink.publisher[String])
        val out10 = b.add(Sink.publisher[String])
        def f(s: String) = b.add(Flow[String].transform(op[String, String]).named(s))
        import FlowGraph.Implicits._

        in7 ~> f("a") ~> b7 ~> f("b") ~> m11 ~> f("c") ~> b11 ~> f("d") ~> out2
        b11 ~> f("e") ~> m9 ~> f("f") ~> out9
        b7 ~> f("g") ~> m8 ~> f("h") ~> m9
        b11 ~> f("i") ~> m10 ~> f("j") ~> out10
        in5 ~> f("k") ~> m11
        in3 ~> f("l") ~> b3 ~> f("m") ~> m8
        b3 ~> f("n") ~> m10
        ClosedShape
      }).run()
    }

    "make it optional to specify flows" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        import FlowGraph.Implicits._
        b.add(in1) ~> merge ~> bcast ~> b.add(out1)
        b.add(in2) ~> merge
        bcast ~> b.add(out2)
        ClosedShape
      }).run()
    }

    "build unzip - zip" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val zip = b.add(Zip[Int, String]())
        val unzip = b.add(Unzip[Int, String]())
        val out = b.add(Sink.publisher[(Int, String)])
        val timesTwo = b.add(Flow[Int].map(_ * 2))
        import FlowGraph.Implicits._
        b.add(Source(List(1 -> "a", 2 -> "b", 3 -> "c"))) ~> unzip.in
        unzip.out0 ~> timesTwo ~> zip.in0
        unzip.out1 ~> zip.in1
        zip.out ~> out
        ClosedShape
      }).run()
    }

    "distinguish between input and output ports" in {
      intercept[IllegalArgumentException] {
        RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
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
          ClosedShape
        })
      }.getMessage should include("must correspond to")
    }

    "build with variance" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        import FlowGraph.Implicits._
        val merge = b.add(Merge[Fruit](2))
        b.add(Source[Fruit](apples)) ~> b.add(Flow[Fruit]) ~> merge.in(0)
        b.add(Source[Apple](apples)) ~> b.add(Flow[Apple]) ~> merge.in(1)
        merge.out ~> b.add(Flow[Fruit].map(identity)) ~> b.add(Sink(TestSubscriber.manualProbe[Fruit]()))
        ClosedShape
      })
    }

    "build with variance when indices are not specified" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        import FlowGraph.Implicits._
        val fruitMerge = b.add(Merge[Fruit](2))
        b.add(Source[Fruit](apples)) ~> fruitMerge
        b.add(Source[Apple](apples)) ~> fruitMerge
        fruitMerge ~> b.add(Sink.head[Fruit])
        "fruitMerge ~> Sink.head[Apple]" shouldNot compile

        val appleMerge = b.add(Merge[Apple](1))
        "Source[Fruit](apples) ~> appleMerge" shouldNot compile
        b.add(Source[Apple](apples)) ~> appleMerge
        appleMerge ~> b.add(Sink.head[Fruit])

        val appleMerge2 = b.add(Merge[Apple](1))
        b.add(Source[Apple](apples)) ~> appleMerge2
        appleMerge2 ~> b.add(Sink.head[Apple])

        val fruitBcast = b.add(Broadcast[Fruit](1))
        b.add(Source[Fruit](apples)) ~> fruitBcast
        //Source[Apple](apples) ~> fruitBcast // FIXME: should compile #16997
        fruitBcast ~> b.add(Sink.head[Fruit])
        "fruitBcast ~> Sink.head[Apple]" shouldNot compile

        val appleBcast = b.add(Broadcast[Apple](2))
        "Source[Fruit](apples) ~> appleBcast" shouldNot compile
        b.add(Source[Apple](apples)) ~> appleBcast
        appleBcast ~> b.add(Sink.head[Fruit])
        appleBcast ~> b.add(Sink.head[Apple])
        ClosedShape
      })
    }

    "build with implicits and variance" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        def appleSource = b.add(Source(TestPublisher.manualProbe[Apple]()))
        def fruitSource = b.add(Source(TestPublisher.manualProbe[Fruit]()))
        val outA = b add Sink(TestSubscriber.manualProbe[Fruit]())
        val outB = b add Sink(TestSubscriber.manualProbe[Fruit]())
        val merge = b add Merge[Fruit](11)
        val unzip = b add Unzip[Int, String]()
        val whatever = b add Sink.publisher[Any]
        def mapFruit = b.add(Flow[Fruit].map(identity))
        def mapApples = b.add(Flow[Apple].map(identity))
        import FlowGraph.Implicits._
        b.add(Source[Fruit](apples)) ~> merge.in(0)
        appleSource ~> merge.in(1)
        appleSource ~> merge.in(2)
        fruitSource ~> merge.in(3)
        fruitSource ~> mapFruit ~> merge.in(4)
        appleSource ~> mapApples ~> merge.in(5)
        b.add(Source(apples)) ~> merge.in(6)
        b.add(Source(apples)) ~> mapFruit ~> merge.in(7)
        b.add(Source(apples)) ~> mapApples ~> merge.in(8)
        merge.out ~> mapFruit ~> outA

        b.add(Source(apples)) ~> b.add(Flow[Apple]) ~> merge.in(9)
        b.add(Source(apples)) ~> b.add(Flow[Apple]) ~> outB
        b.add(Source(apples)) ~> b.add(Flow[Apple]) ~> b.add(Sink.publisher[Fruit])
        appleSource ~> b.add(Flow[Apple]) ~> merge.in(10)

        b.add(Source(List(1 -> "a", 2 -> "b", 3 -> "c"))) ~> unzip.in
        unzip.out1 ~> whatever
        unzip.out0 ~> b.add(Sink.publisher[Any])

        "merge.out ~> b.add(Broadcast[Apple](2))" shouldNot compile
        "merge.out ~> Flow[Fruit].map(identity) ~> b.add(Broadcast[Apple](2))" shouldNot compile
        "fruitSource ~> merge ~> b.add(Broadcast[Apple](2))" shouldNot compile
        ClosedShape
      })
    }

    "build with plain flow without junctions" in {
      import FlowGraph.Implicits._
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        b.add(in1) ~> b.add(f1) ~> b.add(out1)
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        b.add(in1) ~> b.add(f1) ~> b.add(f2.to(out1))
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        b.add(in1 via f1) ~> b.add(f2) ~> b.add(out1)
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        b.add(in1) ~> b.add(out1)
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        b.add(in1) ~> b.add(f1 to out1)
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        b.add(in1 via f1) ~> b.add(out1)
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        b.add(in1 via f1) ~> b.add(f2 to out1)
        ClosedShape
      }).run()
    }
  }
}
