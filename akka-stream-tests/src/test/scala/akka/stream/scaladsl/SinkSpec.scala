/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

import akka.stream.testkit._
import akka.stream.ActorMaterializer

class SinkSpec extends AkkaSpec {
  import FlowGraph.Implicits._

  implicit val mat = ActorMaterializer()
  import mat.executionContext

  "A Sink" must {

    "be composable without importing modules" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](3))
        for (i ← 0 to 2) bcast.out(i).filter(_ == i) ~> Sink(probes(i))
        bcast.in
      }
      Source(List(0, 1, 2)).runWith(sink)
      for (i ← 0 to 2) {
        val p = probes(i)
        val s = p.expectSubscription()
        s.request(3)
        p.expectNext(i)
        p.expectComplete()
      }
    }

    "be composable with importing 1 module" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink(Sink(probes(0))) { implicit b ⇒
        s0 ⇒
          val bcast = b.add(Broadcast[Int](3))
          bcast.out(0) ~> Flow[Int].filter(_ == 0) ~> s0.inlet
          for (i ← 1 to 2) bcast.out(i).filter(_ == i) ~> Sink(probes(i))
          bcast.in
      }
      Source(List(0, 1, 2)).runWith(sink)
      for (i ← 0 to 2) {
        val p = probes(i)
        val s = p.expectSubscription()
        s.request(3)
        p.expectNext(i)
        p.expectComplete()
      }
    }

    "be composable with importing 2 modules" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink(Sink(probes(0)), Sink(probes(1)))(List(_, _)) { implicit b ⇒
        (s0, s1) ⇒
          val bcast = b.add(Broadcast[Int](3))
          bcast.out(0).filter(_ == 0) ~> s0.inlet
          bcast.out(1).filter(_ == 1) ~> s1.inlet
          bcast.out(2).filter(_ == 2) ~> Sink(probes(2))
          bcast.in
      }
      Source(List(0, 1, 2)).runWith(sink)
      for (i ← 0 to 2) {
        val p = probes(i)
        val s = p.expectSubscription()
        s.request(3)
        p.expectNext(i)
        p.expectComplete()
      }
    }

    "be composable with importing 3 modules" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink(Sink(probes(0)), Sink(probes(1)), Sink(probes(2)))(List(_, _, _)) { implicit b ⇒
        (s0, s1, s2) ⇒
          val bcast = b.add(Broadcast[Int](3))
          bcast.out(0).filter(_ == 0) ~> s0.inlet
          bcast.out(1).filter(_ == 1) ~> s1.inlet
          bcast.out(2).filter(_ == 2) ~> s2.inlet
          bcast.in
      }
      Source(List(0, 1, 2)).runWith(sink)
      for (i ← 0 to 2) {
        val p = probes(i)
        val s = p.expectSubscription()
        s.request(3)
        p.expectNext(i)
        p.expectComplete()
      }
    }

  }

  "Sink.toSeq" must {

    "collect all elements when non-empty" in {
      val f = Source(List(1, 2, 3)).runWith(Sink.toSeq)
      Await.result(f, 100.millis) should be(List(1, 2, 3))
    }

    "collect all elements when empty" in {
      val f = Source.empty.runWith(Sink.toSeq)
      Await.result(f, 100.millis) should be(Nil)
    }
  }

  "Sink.single" must {
    "return the sole element if there is one" in {
      val f = Source.single("test").runWith(Sink.single)
      Await.result(f, 100.millis) should be("test")
    }

    "throw if no elements are returned" in {
      val f = Source.empty.runWith(Sink.single)
      Await.ready(f, 100.millis)
      (f.value.get: @unchecked) match {
        case Failure(e) ⇒ e.getMessage should include("empty stream")
      }
    }

    "throw if two elements are returned" in {
      val f = Source(List("a", "b")).runWith(Sink.single)
      Await.ready(f, 100.millis)
      (f.value.get: @unchecked) match {
        case Failure(e) ⇒ e.getMessage should include("Expected exactly one element")
      }
    }
  }
}
