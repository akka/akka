/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.testkit.Utils._
import org.scalactic.ConversionCheckedTripleEquals
import akka.util.ByteString
import akka.stream._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.immutable
import akka.testkit.AkkaSpec

class BidiFlowSpec extends AkkaSpec {
  import Attributes._
  import GraphDSL.Implicits._

  implicit val materializer = ActorMaterializer()

  val bidi = BidiFlow.fromFlows(
    Flow[Int].map(x ⇒ x.toLong + 2).withAttributes(name("top")),
    Flow[ByteString].map(_.decodeString("UTF-8")).withAttributes(name("bottom")))

  val inverse = BidiFlow.fromFlows(
    Flow[Long].map(x ⇒ x.toInt + 2).withAttributes(name("top")),
    Flow[String].map(ByteString(_)).withAttributes(name("bottom")))

  val bidiMat = BidiFlow.fromGraph(GraphDSL.create(Sink.head[Int]) { implicit b ⇒
    s ⇒
      Source.single(42) ~> s

      val top = b.add(Flow[Int].map(x ⇒ x.toLong + 2))
      val bottom = b.add(Flow[ByteString].map(_.decodeString("UTF-8")))
      BidiShape(top.in, top.out, bottom.in, bottom.out)
  })

  val str = "Hello World"
  val bytes = ByteString(str)

  "A BidiFlow" must {

    "work top/bottom in isolation" in {
      val (top, bottom) = RunnableGraph.fromGraph(GraphDSL.create(Sink.head[Long], Sink.head[String])(Keep.both) { implicit b ⇒
        (st, sb) ⇒
          val s = b.add(bidi)

          Source.single(1) ~> s.in1; s.out1 ~> st
          sb <~ s.out2; s.in2 <~ Source.single(bytes)
          ClosedShape
      }).run()

      Await.result(top, 1.second) should ===(3)
      Await.result(bottom, 1.second) should ===(str)
    }

    "work as a Flow that is open on the left" in {
      val f = bidi.join(Flow[Long].map(x ⇒ ByteString(s"Hello $x")))
      val result = Source(List(1, 2, 3)).via(f).limit(10).runWith(Sink.seq)
      Await.result(result, 1.second) should ===(Seq("Hello 3", "Hello 4", "Hello 5"))
    }

    "work as a Flow that is open on the right" in {
      val f = Flow[String].map(Integer.valueOf(_).toInt).join(bidi)
      val result = Source(List(ByteString("1"), ByteString("2"))).via(f).limit(10).runWith(Sink.seq)
      Await.result(result, 1.second) should ===(Seq(3L, 4L))
    }

    "work when atop its inverse" in {
      val f = bidi.atop(inverse).join(Flow[Int].map(_.toString))
      val result = Source(List(1, 2, 3)).via(f).limit(10).runWith(Sink.seq)
      Await.result(result, 1.second) should ===(Seq("5", "6", "7"))
    }

    "work when reversed" in {
      // just reversed from the case above; observe that Flow inverts itself automatically by being on the left side
      val f = Flow[Int].map(_.toString).join(inverse.reversed).join(bidi.reversed)
      val result = Source(List(1, 2, 3)).via(f).limit(10).runWith(Sink.seq)
      Await.result(result, 1.second) should ===(Seq("5", "6", "7"))
    }

    "materialize to its value" in {
      val f = RunnableGraph.fromGraph(GraphDSL.create(bidiMat) { implicit b ⇒
        bidi ⇒
          Flow[String].map(Integer.valueOf(_).toInt) <~> bidi <~> Flow[Long].map(x ⇒ ByteString(s"Hello $x"))
          ClosedShape
      }).run()
      Await.result(f, 1.second) should ===(42)
    }

    "combine materialization values" in assertAllStagesStopped {
      val left = Flow.fromGraph(GraphDSL.create(Sink.head[Int]) { implicit b ⇒
        sink ⇒
          val bcast = b.add(Broadcast[Int](2))
          val merge = b.add(Merge[Int](2))
          val flow = b.add(Flow[String].map(Integer.valueOf(_).toInt))
          bcast ~> sink
          Source.single(1) ~> bcast ~> merge
          flow ~> merge
          FlowShape(flow.in, merge.out)
      })
      val right = Flow.fromGraph(GraphDSL.create(Sink.head[immutable.Seq[Long]]) { implicit b ⇒
        sink ⇒
          val flow = b.add(Flow[Long].grouped(10))
          flow ~> sink
          FlowShape(flow.in, b.add(Source.single(ByteString("10"))).out)
      })
      val ((l, m), r) = left.joinMat(bidiMat)(Keep.both).joinMat(right)(Keep.both).run()
      Await.result(l, 1.second) should ===(1)
      Await.result(m, 1.second) should ===(42)
      Await.result(r, 1.second).toSet should ===(Set(3L, 12L))
    }

    "suitably override attribute handling methods" in {
      import Attributes._
      val b: BidiFlow[Int, Long, ByteString, String, NotUsed] = bidi.withAttributes(name("")).async.named("")
    }

  }

}
