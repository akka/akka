package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream._
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalactic.ConversionCheckedTripleEquals

class ReverseArrowSpec extends AkkaSpec with ConversionCheckedTripleEquals {
  import FlowGraph.Implicits._

  implicit val mat = ActorFlowMaterializer()
  val source = Source(List(1, 2, 3))
  val sink = Flow[Int].grouped(10).toMat(Sink.head)(Keep.right)

  "Reverse Arrows in the Graph DSL" must {

    "work from Inlets" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          s.inlet <~ source
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work from SinkShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          s <~ source
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work from Sink" in {
      val sub = TestSubscriber.manualProbe[Int]
      FlowGraph.closed() { implicit b ⇒
        Sink(sub) <~ source
      }.run()
      sub.expectSubscription().request(10)
      sub.expectNext(1, 2, 3)
      sub.expectComplete()
    }

    "not work from Outlets" in {
      FlowGraph.closed() { implicit b ⇒
        val o: Outlet[Int] = b.add(source)
        "o <~ source" shouldNot compile
        sink <~ o
      }
    }

    "not work from SourceShape" in {
      FlowGraph.closed() { implicit b ⇒
        val o: SourceShape[Int] = b.add(source)
        "o <~ source" shouldNot compile
        sink <~ o
      }
    }

    "not work from Source" in {
      "source <~ source" shouldNot compile
    }

    "work from FlowShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: FlowShape[Int, Int] = b.add(Flow[Int])
          f <~ source
          f ~> s
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work from UniformFanInShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanInShape[Int, Int] = b.add(Merge[Int](1))
          f <~ source
          f ~> s
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work from UniformFanOutShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanOutShape[Int, Int] = b.add(Broadcast[Int](1))
          f <~ source
          f ~> s
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards Outlets" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val o: Outlet[Int] = b.add(source)
          s <~ o
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards SourceShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val o: SourceShape[Int] = b.add(source)
          s <~ o
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards Source" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          s <~ source
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards FlowShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: FlowShape[Int, Int] = b.add(Flow[Int])
          s <~ f
          source ~> f
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards UniformFanInShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanInShape[Int, Int] = b.add(Merge[Int](1))
          s <~ f
          source ~> f
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "fail towards already full UniformFanInShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanInShape[Int, Int] = b.add(Merge[Int](1))
          val src = b.add(source)
          src ~> f
          (the[IllegalArgumentException] thrownBy (s <~ f <~ src)).getMessage should include("no more inlets free")
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work towards UniformFanOutShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanOutShape[Int, Int] = b.add(Broadcast[Int](1))
          s <~ f
          source ~> f
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "fail towards already full UniformFanOutShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          val f: UniformFanOutShape[Int, Int] = b.add(Broadcast[Int](1))
          val src = b.add(source)
          src ~> f
          (the[IllegalArgumentException] thrownBy (s <~ f <~ src)).getMessage should include("already connected")
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work across a Flow" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          s <~ Flow[Int] <~ source
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

    "work across a FlowShape" in {
      Await.result(FlowGraph.closed(sink) { implicit b ⇒
        s ⇒
          s <~ b.add(Flow[Int]) <~ source
      }.run(), 1.second) should ===(Seq(1, 2, 3))
    }

  }

}
