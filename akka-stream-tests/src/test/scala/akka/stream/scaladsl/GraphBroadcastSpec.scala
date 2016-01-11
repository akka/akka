package akka.stream.scaladsl

import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

import akka.stream._
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class GraphBroadcastSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A broadcast" must {
    import GraphDSL.Implicits._

    "broadcast to other subscriber" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](2))
        Source(List(1, 2, 3)) ~> bcast.in
        bcast.out(0) ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink(c1)
        bcast.out(1) ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink(c2)
        ClosedShape
      }).run()

      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNoMsg(100.millis)
      sub1.request(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
      sub2.request(3)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "work with n-way broadcast" in assertAllStagesStopped {
      val headSink = Sink.head[Seq[Int]]

      import system.dispatcher
      val result = RunnableGraph.fromGraph(GraphDSL.create(
        headSink,
        headSink,
        headSink,
        headSink,
        headSink)(
          (fut1, fut2, fut3, fut4, fut5) ⇒ Future.sequence(List(fut1, fut2, fut3, fut4, fut5))) { implicit b ⇒
            (p1, p2, p3, p4, p5) ⇒
              val bcast = b.add(Broadcast[Int](5))
              Source(List(1, 2, 3)) ~> bcast.in
              bcast.out(0).grouped(5) ~> p1.inlet
              bcast.out(1).grouped(5) ~> p2.inlet
              bcast.out(2).grouped(5) ~> p3.inlet
              bcast.out(3).grouped(5) ~> p4.inlet
              bcast.out(4).grouped(5) ~> p5.inlet
              ClosedShape
          }).run()

      Await.result(result, 3.seconds) should be(List.fill(5)(List(1, 2, 3)))
    }

    "work with 22-way broadcast" in assertAllStagesStopped {
      type T = Seq[Int]
      type FT = Future[Seq[Int]]
      val headSink: Sink[T, FT] = Sink.head[T]

      import system.dispatcher
      val combine: (FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT, FT) ⇒ Future[Seq[Seq[Int]]] =
        (f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f20, f21, f22) ⇒
          Future.sequence(List(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f20, f21, f22))

      val result = RunnableGraph.fromGraph(GraphDSL.create(
        headSink, headSink, headSink, headSink, headSink,
        headSink, headSink, headSink, headSink, headSink,
        headSink, headSink, headSink, headSink, headSink,
        headSink, headSink, headSink, headSink, headSink,
        headSink, headSink)(combine) {
          implicit b ⇒
            (p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22) ⇒
              val bcast = b.add(Broadcast[Int](22))
              Source(List(1, 2, 3)) ~> bcast.in
              bcast.out(0).grouped(5) ~> p1.inlet
              bcast.out(1).grouped(5) ~> p2.inlet
              bcast.out(2).grouped(5) ~> p3.inlet
              bcast.out(3).grouped(5) ~> p4.inlet
              bcast.out(4).grouped(5) ~> p5.inlet
              bcast.out(5).grouped(5) ~> p6.inlet
              bcast.out(6).grouped(5) ~> p7.inlet
              bcast.out(7).grouped(5) ~> p8.inlet
              bcast.out(8).grouped(5) ~> p9.inlet
              bcast.out(9).grouped(5) ~> p10.inlet
              bcast.out(10).grouped(5) ~> p11.inlet
              bcast.out(11).grouped(5) ~> p12.inlet
              bcast.out(12).grouped(5) ~> p13.inlet
              bcast.out(13).grouped(5) ~> p14.inlet
              bcast.out(14).grouped(5) ~> p15.inlet
              bcast.out(15).grouped(5) ~> p16.inlet
              bcast.out(16).grouped(5) ~> p17.inlet
              bcast.out(17).grouped(5) ~> p18.inlet
              bcast.out(18).grouped(5) ~> p19.inlet
              bcast.out(19).grouped(5) ~> p20.inlet
              bcast.out(20).grouped(5) ~> p21.inlet
              bcast.out(21).grouped(5) ~> p22.inlet
              ClosedShape
        }).run()

      Await.result(result, 3.seconds) should be(List.fill(22)(List(1, 2, 3)))
    }

    "produce to other even though downstream cancels" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](2))
        Source(List(1, 2, 3)) ~> bcast.in
        bcast.out(0) ~> Flow[Int] ~> Sink(c1)
        bcast.out(1) ~> Flow[Int] ~> Sink(c2)
        ClosedShape
      }).run()

      val sub1 = c1.expectSubscription()
      sub1.cancel()
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "produce to downstream even though other cancels" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](2))
        Source(List(1, 2, 3)) ~> bcast.in
        bcast.out(0) ~> Flow[Int].named("identity-a") ~> Sink(c1)
        bcast.out(1) ~> Flow[Int].named("identity-b") ~> Sink(c2)
        ClosedShape
      }).run()

      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub2.cancel()
      sub1.request(3)
      c1.expectNext(1)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
    }

    "cancel upstream when downstreams cancel" in assertAllStagesStopped {
      val p1 = TestPublisher.manualProbe[Int]()
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](2))
        Source(p1.getPublisher) ~> bcast.in
        bcast.out(0) ~> Flow[Int] ~> Sink(c1)
        bcast.out(1) ~> Flow[Int] ~> Sink(c2)
        ClosedShape
      }).run()

      val bsub = p1.expectSubscription()
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(3)
      sub2.request(3)
      p1.expectRequest(bsub, 16)
      bsub.sendNext(1)
      c1.expectNext(1)
      c2.expectNext(1)
      bsub.sendNext(2)
      c1.expectNext(2)
      c2.expectNext(2)
      sub1.cancel()
      sub2.cancel()
      bsub.expectCancellation()
    }

    "pass along early cancellation" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      val sink = Sink.fromGraph(GraphDSL.create() { implicit b ⇒
        val bcast = b.add(Broadcast[Int](2))
        bcast.out(0) ~> Sink(c1)
        bcast.out(1) ~> Sink(c2)
        SinkShape(bcast.in)
      })

      val s = Source.subscriber[Int].to(sink).run()

      val up = TestPublisher.manualProbe[Int]()

      val downsub1 = c1.expectSubscription()
      val downsub2 = c2.expectSubscription()
      downsub1.cancel()
      downsub2.cancel()

      up.subscribe(s)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

    "alsoTo must broadcast" in assertAllStagesStopped {
      val p, p2 = TestSink.probe[Int](system)
      val (ps1, ps2) = Source(1 to 6).alsoToMat(p)(Keep.right).toMat(p2)(Keep.both).run()
      ps1.request(6)
      ps2.request(6)
      ps1.expectNext(1, 2, 3, 4, 5, 6)
      ps2.expectNext(1, 2, 3, 4, 5, 6)
      ps1.expectComplete()
      ps2.expectComplete()
    }

    "alsoTo must continue if sink cancels" in assertAllStagesStopped {
      val p, p2 = TestSink.probe[Int](system)
      val (ps1, ps2) = Source(1 to 6).alsoToMat(p)(Keep.right).toMat(p2)(Keep.both).run()
      ps2.request(6)
      ps1.cancel()
      ps2.expectNext(1, 2, 3, 4, 5, 6)
      ps2.expectComplete()
    }
  }

}
