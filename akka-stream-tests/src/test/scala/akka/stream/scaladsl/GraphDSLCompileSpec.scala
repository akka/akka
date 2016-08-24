/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.impl.fusing.GraphStages
import akka.stream.{ ActorMaterializer, ClosedShape, FlowShape, OverflowStrategy }
import akka.stream.testkit._
import akka.stream.stage._

object GraphDSLCompileSpec {
  class Fruit
  class Apple extends Fruit
}

class GraphDSLCompileSpec extends StreamSpec {
  import GraphDSLCompileSpec._

  implicit val materializer = ActorMaterializer()

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
  val out1 = Sink.asPublisher[String](false)
  val out2 = Sink.head[String]

  "A Graph" should {
    import GraphDSL.Implicits._
    "build simple merge" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        in1 ~> f1 ~> merge.in(0)
        in2 ~> f2 ~> merge.in(1)
        merge.out ~> f3 ~> out1
        ClosedShape
      }).run()
    }

    "build simple broadcast" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[String](2))
        in1 ~> f1 ~> bcast.in
        bcast.out(0) ~> f2 ~> out1
        bcast.out(1) ~> f3 ~> out2
        ClosedShape
      }).run()
    }

    "build simple balance" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val balance = b.add(Balance[String](2))
        in1 ~> f1 ~> balance.in
        balance.out(0) ~> f2 ~> out1
        balance.out(1) ~> f3 ~> out2
        ClosedShape
      })
    }

    "build simple merge - broadcast" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        in1 ~> f1 ~> merge.in(0)
        in2 ~> f2 ~> merge.in(1)
        merge ~> f3 ~> bcast
        bcast.out(0) ~> f4 ~> out1
        bcast.out(1) ~> f5 ~> out2
        ClosedShape
      }).run()
    }

    "build simple merge - broadcast with implicits" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        b.add(in1) ~> f1 ~> merge.in(0)
        merge.out ~> f2 ~> bcast.in
        bcast.out(0) ~> f3 ~> b.add(out1)
        b.add(in2) ~> f4 ~> merge.in(1)
        bcast.out(1) ~> f5 ~> b.add(out2)
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
        RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
          val merge = b.add(Merge[String](2))
          val bcast1 = b.add(Broadcast[String](2))
          val bcast2 = b.add(Broadcast[String](2))
          val feedbackLoopBuffer = Flow[String].buffer(10, OverflowStrategy.dropBuffer)
          in1 ~> f1 ~> merge.in(0)
          merge ~> f2 ~> bcast1
          bcast1.out(0) ~> f3 ~> out1
          bcast1.out(1) ~> feedbackLoopBuffer ~> bcast2
          bcast2.out(0) ~> f5 ~> merge.in(1) // cycle
          bcast2.out(1) ~> f6 ~> out2
          ClosedShape
        })
      }.getMessage.toLowerCase should include("cycle")

    }

    "express complex topologies in a readable way" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast1 = b.add(Broadcast[String](2))
        val bcast2 = b.add(Broadcast[String](2))
        val feedbackLoopBuffer = Flow[String].buffer(10, OverflowStrategy.dropBuffer)
        import GraphDSL.Implicits._
        b.add(in1) ~> f1 ~> merge ~> f2 ~> bcast1 ~> f3 ~> b.add(out1)
        bcast1 ~> feedbackLoopBuffer ~> bcast2 ~> f5 ~> merge
        bcast2 ~> f6 ~> b.add(out2)
        ClosedShape
      }).run()
    }

    "build broadcast - merge" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[String](2))
        val merge = b.add(Merge[String](2))
        import GraphDSL.Implicits._
        in1 ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out1
        bcast ~> f4 ~> merge
        ClosedShape
      }).run()
    }

    "build wikipedia Topological_sorting" in {
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
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
        val out2 = Sink.asPublisher[String](false)
        val out9 = Sink.asPublisher[String](false)
        val out10 = Sink.asPublisher[String](false)
        def f(s: String) = Flow[String].transform(op[String, String]).named(s)
        import GraphDSL.Implicits._

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
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val merge = b.add(Merge[String](2))
        val bcast = b.add(Broadcast[String](2))
        import GraphDSL.Implicits._
        in1 ~> merge ~> bcast ~> out1
        in2 ~> merge
        bcast ~> out2
        ClosedShape
      }).run()
    }

    "build unzip - zip" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val zip = b.add(Zip[Int, String]())
        val unzip = b.add(Unzip[Int, String]())
        val out = Sink.asPublisher[(Int, String)](false)
        import GraphDSL.Implicits._
        Source(List(1 → "a", 2 → "b", 3 → "c")) ~> unzip.in
        unzip.out0 ~> Flow[Int].map(_ * 2) ~> zip.in0
        unzip.out1 ~> zip.in1
        zip.out ~> out
        ClosedShape
      }).run()
    }

    "distinguish between input and output ports" in {
      intercept[IllegalArgumentException] {
        RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
          val zip = b.add(Zip[Int, String]())
          val unzip = b.add(Unzip[Int, String]())
          val wrongOut = Sink.asPublisher[(Int, Int)](false)
          val whatever = Sink.asPublisher[Any](false)
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
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val merge = b.add(Merge[Fruit](2))
        Source.fromIterator[Fruit](apples) ~> Flow[Fruit] ~> merge.in(0)
        Source.fromIterator[Apple](apples) ~> Flow[Apple] ~> merge.in(1)
        merge.out ~> Flow[Fruit].map(identity) ~> Sink.fromSubscriber(TestSubscriber.manualProbe[Fruit]())
        ClosedShape
      })
    }

    "build with variance when indices are not specified" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val fruitMerge = b.add(Merge[Fruit](2))
        Source.fromIterator[Fruit](apples) ~> fruitMerge
        Source.fromIterator[Apple](apples) ~> fruitMerge
        fruitMerge ~> Sink.head[Fruit]
        "fruitMerge ~> Sink.head[Apple]" shouldNot compile

        val appleMerge = b.add(Merge[Apple](2))
        "Source[Fruit](apples) ~> appleMerge" shouldNot compile
        Source.empty[Apple] ~> appleMerge
        Source.fromIterator[Apple](apples) ~> appleMerge
        appleMerge ~> Sink.head[Fruit]

        val appleMerge2 = b.add(Merge[Apple](2))
        Source.empty[Apple] ~> appleMerge2
        Source.fromIterator[Apple](apples) ~> appleMerge2
        appleMerge2 ~> Sink.head[Apple]

        val fruitBcast = b.add(Broadcast[Fruit](2))
        Source.fromIterator[Apple](apples) ~> fruitBcast
        fruitBcast ~> Sink.head[Fruit]
        fruitBcast ~> Sink.ignore
        "fruitBcast ~> Sink.head[Apple]" shouldNot compile

        val appleBcast = b.add(Broadcast[Apple](2))
        "Source[Fruit](apples) ~> appleBcast" shouldNot compile
        Source.fromIterator[Apple](apples) ~> appleBcast
        appleBcast ~> Sink.head[Fruit]
        appleBcast ~> Sink.head[Apple]
        ClosedShape
      })
    }

    "build with implicits and variance" in {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        def appleSource = b.add(Source.fromPublisher(TestPublisher.manualProbe[Apple]()))
        def fruitSource = b.add(Source.fromPublisher(TestPublisher.manualProbe[Fruit]()))
        val outA = b add Sink.fromSubscriber(TestSubscriber.manualProbe[Fruit]())
        val outB = b add Sink.fromSubscriber(TestSubscriber.manualProbe[Fruit]())
        val merge = b add Merge[Fruit](11)
        val unzip = b add Unzip[Int, String]()
        val whatever = b add Sink.asPublisher[Any](false)
        import GraphDSL.Implicits._
        b.add(Source.fromIterator[Fruit](apples)) ~> merge.in(0)
        appleSource ~> merge.in(1)
        appleSource ~> merge.in(2)
        fruitSource ~> merge.in(3)
        fruitSource ~> Flow[Fruit].map(identity) ~> merge.in(4)
        appleSource ~> Flow[Apple].map(identity) ~> merge.in(5)
        b.add(Source.fromIterator(apples)) ~> merge.in(6)
        b.add(Source.fromIterator(apples)) ~> Flow[Fruit].map(identity) ~> merge.in(7)
        b.add(Source.fromIterator(apples)) ~> Flow[Apple].map(identity) ~> merge.in(8)
        merge.out ~> Flow[Fruit].map(identity) ~> outA

        b.add(Source.fromIterator(apples)) ~> Flow[Apple] ~> merge.in(9)
        b.add(Source.fromIterator(apples)) ~> Flow[Apple] ~> outB
        b.add(Source.fromIterator(apples)) ~> Flow[Apple] ~> b.add(Sink.asPublisher[Fruit](false))
        appleSource ~> Flow[Apple] ~> merge.in(10)

        Source(List(1 → "a", 2 → "b", 3 → "c")) ~> unzip.in
        unzip.out1 ~> whatever
        unzip.out0 ~> b.add(Sink.asPublisher[Any](false))

        "merge.out ~> b.add(Broadcast[Apple](2))" shouldNot compile
        "merge.out ~> Flow[Fruit].map(identity) ~> b.add(Broadcast[Apple](2))" shouldNot compile
        "fruitSource ~> merge ~> b.add(Broadcast[Apple](2))" shouldNot compile
        ClosedShape
      })
    }

    "build with plain flow without junctions" in {
      import GraphDSL.Implicits._
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        in1 ~> f1 ~> out1
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        in1 ~> f1 ~> f2.to(out1)
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        (in1 via f1) ~> f2 ~> out1
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        in1 ~> out1
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        in1 ~> (f1 to out1)
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        (in1 via f1) ~> out1
        ClosedShape
      }).run()
      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        (in1 via f1) ~> (f2 to out1)
        ClosedShape
      }).run()
    }

    "suitably override attribute handling methods" in {
      import akka.stream.Attributes._
      val ga = GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val id = b.add(GraphStages.Identity)

        FlowShape(id.in, id.out)
      }.async.addAttributes(none).named("useless")

      ga.module.attributes.getFirst[Name] shouldEqual Some(Name("useless"))
      ga.module.attributes.getFirst[AsyncBoundary.type] shouldEqual (Some(AsyncBoundary))
    }
  }
}
