/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.AkkaSpec
import org.scalactic.ConversionCheckedTripleEquals
import akka.util.ByteString
import akka.stream.BidiShape
import akka.stream.ActorFlowMaterializer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.immutable
import akka.stream.OperationAttributes

class BidiFlowSpec extends AkkaSpec with ConversionCheckedTripleEquals {
  import OperationAttributes._
  import FlowGraph.Implicits._

  implicit val mat = ActorFlowMaterializer()

  val bidi = BidiFlow() { b ⇒
    val top = b.add(Flow[Int].map(x ⇒ x.toLong + 2).withAttributes(name("top")))
    val bottom = b.add(Flow[ByteString].map(_.decodeString("UTF-8")).withAttributes(name("bottom")))
    BidiShape(top.inlet, top.outlet, bottom.inlet, bottom.outlet)
  }

  val inverse = BidiFlow() { b ⇒
    val top = b.add(Flow[Long].map(x ⇒ x.toInt + 2).withAttributes(name("top")))
    val bottom = b.add(Flow[String].map(ByteString(_)).withAttributes(name("bottom")))
    BidiShape(top.inlet, top.outlet, bottom.inlet, bottom.outlet)
  }

  val bidiMat = BidiFlow(Sink.head[Int]) { implicit b ⇒
    s ⇒
      Source.single(42) ~> s

      val top = b.add(Flow[Int].map(x ⇒ x.toLong + 2))
      val bottom = b.add(Flow[ByteString].map(_.decodeString("UTF-8")))
      BidiShape(top.inlet, top.outlet, bottom.inlet, bottom.outlet)
  }

  val str = "Hello World"
  val bytes = ByteString(str)

  "A BidiFlow" must {

    "work top/bottom in isolation" in {
      val (top, bottom) = FlowGraph.closed(Sink.head[Long], Sink.head[String])(Keep.both) { implicit b ⇒
        (st, sb) ⇒
          val s = b.add(bidi)

          Source.single(1) ~> s.in1; s.out1 ~> st
          sb <~ s.out2; s.in2 <~ Source.single(bytes)
      }.run()

      Await.result(top, 1.second) should ===(3)
      Await.result(bottom, 1.second) should ===(str)
    }

    "work as a Flow that is open on the left" in {
      val f = bidi.join(Flow[Long].map(x ⇒ ByteString(s"Hello $x")))
      val result = Source(List(1, 2, 3)).via(f).grouped(10).runWith(Sink.head)
      Await.result(result, 1.second) should ===(Seq("Hello 3", "Hello 4", "Hello 5"))
    }

    "work as a Flow that is open on the right" in {
      val f = Flow[String].map(Integer.valueOf(_).toInt).join(bidi)
      val result = Source(List(ByteString("1"), ByteString("2"))).via(f).grouped(10).runWith(Sink.head)
      Await.result(result, 1.second) should ===(Seq(3L, 4L))
    }

    "work when atop its inverse" in {
      val f = bidi.atop(inverse).join(Flow[Int].map(_.toString))
      val result = Source(List(1, 2, 3)).via(f).grouped(10).runWith(Sink.head)
      Await.result(result, 1.second) should ===(Seq("5", "6", "7"))
    }

    "work when reversed" in {
      // just reversed from the case above; observe that Flow inverts itself automatically by being on the left side
      val f = Flow[Int].map(_.toString).join(inverse.reversed).join(bidi.reversed)
      val result = Source(List(1, 2, 3)).via(f).grouped(10).runWith(Sink.head)
      Await.result(result, 1.second) should ===(Seq("5", "6", "7"))
    }

    "materialize to its value" in {
      val f = FlowGraph.closed(bidiMat) { implicit b ⇒
        bidi ⇒
          Flow[String].map(Integer.valueOf(_).toInt) <~> bidi <~> Flow[Long].map(x ⇒ ByteString(s"Hello $x"))
      }.run()
      Await.result(f, 1.second) should ===(42)
    }

    "combine materialization values" in {
      val left = Flow(Sink.head[Int]) { implicit b ⇒
        sink ⇒
          val bcast = b.add(Broadcast[Int](2))
          val merge = b.add(Merge[Int](2))
          val flow = b.add(Flow[String].map(Integer.valueOf(_).toInt))
          bcast ~> sink
          Source.single(1) ~> bcast ~> merge
          flow ~> merge
          (flow.inlet, merge.out)
      }
      val right = Flow(Sink.head[immutable.Seq[Long]]) { implicit b ⇒
        sink ⇒
          val flow = b.add(Flow[Long].grouped(10))
          flow ~> sink
          (flow.inlet, b.add(Source.single(ByteString("10"))))
      }
      val ((l, m), r) = left.joinMat(bidiMat)(Keep.both).joinMat(right)(Keep.both).run()
      Await.result(l, 1.second) should ===(1)
      Await.result(m, 1.second) should ===(42)
      Await.result(r, 1.second).toSet should ===(Set(3L, 12L))
    }

  }

}
