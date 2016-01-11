/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ SinkShape, ActorMaterializer }
import akka.stream.testkit.TestPublisher.ManualProbe
import akka.stream.testkit._

class SinkSpec extends AkkaSpec {

  import GraphDSL.Implicits._

  implicit val mat = ActorMaterializer()

  "A Sink" must {

    "be composable without importing modules" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](3))
        for (i ← 0 to 2) bcast.out(i).filter(_ == i) ~> Sink(probes(i))
        SinkShape(bcast.in)
      })
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
      val sink = Sink.fromGraph(GraphDSL.create(Sink(probes(0))) { implicit b ⇒
        s0 ⇒
          val bcast = b.add(Broadcast[Int](3))
          bcast.out(0) ~> Flow[Int].filter(_ == 0) ~> s0.inlet
          for (i ← 1 to 2) bcast.out(i).filter(_ == i) ~> Sink(probes(i))
          SinkShape(bcast.in)
      })
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
      val sink = Sink.fromGraph(GraphDSL.create(Sink(probes(0)), Sink(probes(1)))(List(_, _)) { implicit b ⇒
        (s0, s1) ⇒
          val bcast = b.add(Broadcast[Int](3))
          bcast.out(0).filter(_ == 0) ~> s0.inlet
          bcast.out(1).filter(_ == 1) ~> s1.inlet
          bcast.out(2).filter(_ == 2) ~> Sink(probes(2))
          SinkShape(bcast.in)
      })
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
      val sink = Sink.fromGraph(GraphDSL.create(Sink(probes(0)), Sink(probes(1)), Sink(probes(2)))(List(_, _, _)) { implicit b ⇒
        (s0, s1, s2) ⇒
          val bcast = b.add(Broadcast[Int](3))
          bcast.out(0).filter(_ == 0) ~> s0.inlet
          bcast.out(1).filter(_ == 1) ~> s1.inlet
          bcast.out(2).filter(_ == 2) ~> s2.inlet
          SinkShape(bcast.in)
      })
      Source(List(0, 1, 2)).runWith(sink)
      for (i ← 0 to 2) {
        val p = probes(i)
        val s = p.expectSubscription()
        s.request(3)
        p.expectNext(i)
        p.expectComplete()
      }
    }

    "combine to many outputs with simplified API" in {
      val probes = Seq.fill(3)(TestSubscriber.manualProbe[Int]())
      val sink = Sink.combine(Sink(probes(0)), Sink(probes(1)), Sink(probes(2)))(Broadcast[Int](_))

      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())

      subscriptions.foreach { s ⇒ s.request(1) }
      probes.foreach { p ⇒ p.expectNext(0) }

      subscriptions.foreach { s ⇒ s.request(2) }
      probes.foreach { p ⇒
        p.expectNextN(List(1, 2))
        p.expectComplete
      }
    }

    "combine to two sinks with simplified API" in {
      val probes = Seq.fill(2)(TestSubscriber.manualProbe[Int]())
      val sink = Sink.combine(Sink(probes(0)), Sink(probes(1)))(Broadcast[Int](_))

      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())

      subscriptions.foreach { s ⇒ s.request(1) }
      probes.foreach { p ⇒ p.expectNext(0) }

      subscriptions.foreach { s ⇒ s.request(2) }
      probes.foreach { p ⇒
        p.expectNextN(List(1, 2))
        p.expectComplete
      }
    }

  }

}