package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream._
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalactic.ConversionCheckedTripleEquals

class ReverseArrowSpec extends AkkaSpec with ConversionCheckedTripleEquals {
  import FlowGraph.Implicits._

  implicit val mat = ActorMaterializer()
  val source = Source(List(1, 2, 3))
  val sink = Flow[Int].grouped(10).toMat(Sink.head)(Keep.right)

  "Reverse Arrows in the Graph DSL" must {

    "work from Inlets" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          "s.inlet <~ source" shouldNot compile
          s.inlet <~ b.add(source)
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work from SinkShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          "s <~ source" shouldNot compile
          s <~ b.add(source)
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "not work from Sink" in {
      val sub = TestSubscriber.manualProbe[Int]
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        "Sink(sub) <~ source" shouldNot compile
        b.add(Sink(sub)) <~ b.add(source)
        ClosedShape
      }).run()
      sub.expectSubscription().request(10)
      sub.expectNext(1, 2, 3)
      sub.expectComplete()
    }

    "not work from Outlets" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val o: Outlet[Int] = b.add(source).outlet
        "o <~ source" shouldNot compile
        b.add(sink) <~ o
        ClosedShape
      })
    }

    "not work from SourceShape" in {
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
        val o: SourceShape[Int] = b.add(source)
        "o <~ source" shouldNot compile
        b.add(sink) <~ o
        ClosedShape
      })
    }

    "not work from Source" in {
      "source <~ source" shouldNot compile
    }

    "work from FlowShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: FlowShape[Int, Int] = b.add(Flow[Int])
          f <~ b.add(source)
          f ~> s
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work from UniformFanInShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanInShape[Int, Int] = b.add(Merge[Int](1))
          f <~ b.add(source)
          f ~> s
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work from UniformFanOutShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanOutShape[Int, Int] = b.add(Broadcast[Int](1))
          f <~ b.add(source)
          f ~> s
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards Outlets" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val o: Outlet[Int] = b.add(source).outlet
          s <~ o
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards SourceShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val o: SourceShape[Int] = b.add(source)
          s <~ o
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "not work towards Source" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          "s <~ source" shouldNot compile
          s <~ b.add(source)
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards FlowShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: FlowShape[Int, Int] = b.add(Flow[Int])
          s <~ f
          b.add(source) ~> f
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards UniformFanInShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanInShape[Int, Int] = b.add(Merge[Int](1))
          s <~ f
          b.add(source) ~> f
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "fail towards already full UniformFanInShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanInShape[Int, Int] = b.add(Merge[Int](1))
          val src = b.add(source)
          src ~> f
          (the[IllegalArgumentException] thrownBy (s <~ f <~ src)).getMessage should include("no more inlets free")
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards UniformFanOutShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanOutShape[Int, Int] = b.add(Broadcast[Int](1))
          s <~ f
          b.add(source) ~> f
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "fail towards already full UniformFanOutShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanOutShape[Int, Int] = b.add(Broadcast[Int](1))
          val src = b.add(source)
          src ~> f
          (the[IllegalArgumentException] thrownBy (s <~ f <~ src)).getMessage should include("already connected")
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "not work across a Flow" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          "s <~ Flow[Int] <~ b.add(source)" shouldNot compile
          s <~ b.add(Flow[Int]) <~ b.add(source)
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work across a FlowShape" in {
      Await.result(RunnableGraph.fromGraph(FlowGraph.create(sink) { implicit b ⇒
        s ⇒
          s <~ b.add(Flow[Int]) <~ b.add(source)
          ClosedShape
      }).run(), 1.second) should ===(Seq(1, 2, 3))
    }

  }

}
