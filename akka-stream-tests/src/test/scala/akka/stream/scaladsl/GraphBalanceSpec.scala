package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.stream.ActorFlowMaterializer

import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class GraphBalanceSpec extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorFlowMaterializer(settings)

  "A balance" must {
    import FlowGraph.Implicits._

    "balance between subscribers which signal demand" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      FlowGraph.closed() { implicit b ⇒
        val balance = b.add(Balance[Int](2))
        Source(List(1, 2, 3)) ~> balance.in
        balance.out(0) ~> Sink(c1)
        balance.out(1) ~> Sink(c2)
      }.run()

      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()

      sub1.request(1)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)

      sub2.request(2)
      c2.expectNext(2)
      c2.expectNext(3)
      c1.expectComplete()
      c2.expectComplete()
    }

    "support waiting for demand from all downstream subscriptions" in {
      val s1 = TestSubscriber.manualProbe[Int]()
      val p2 = FlowGraph.closed(Sink.publisher[Int]) { implicit b ⇒
        p2Sink ⇒
          val balance = b.add(Balance[Int](2, waitForAllDownstreams = true))
          Source(List(1, 2, 3)) ~> balance.in
          balance.out(0) ~> Sink(s1)
          balance.out(1) ~> p2Sink.inlet
      }.run()

      val sub1 = s1.expectSubscription()
      sub1.request(1)
      s1.expectNoMsg(200.millis)

      val s2 = TestSubscriber.manualProbe[Int]()
      p2.subscribe(s2)
      val sub2 = s2.expectSubscription()

      // still no demand from s2
      s1.expectNoMsg(200.millis)

      sub2.request(2)
      s1.expectNext(1)
      s2.expectNext(2)
      s2.expectNext(3)
      s1.expectComplete()
      s2.expectComplete()
    }

    "support waiting for demand from all non-cancelled downstream subscriptions" in assertAllStagesStopped {
      val s1 = TestSubscriber.manualProbe[Int]()

      val (p2, p3) = FlowGraph.closed(Sink.publisher[Int], Sink.publisher[Int])(Keep.both) { implicit b ⇒
        (p2Sink, p3Sink) ⇒
          val balance = b.add(Balance[Int](3, waitForAllDownstreams = true))
          Source(List(1, 2, 3)) ~> balance.in
          balance.out(0) ~> Sink(s1)
          balance.out(1) ~> p2Sink.inlet
          balance.out(2) ~> p3Sink.inlet
      }.run()

      val sub1 = s1.expectSubscription()
      sub1.request(1)

      val s2 = TestSubscriber.manualProbe[Int]()
      p2.subscribe(s2)
      val sub2 = s2.expectSubscription()

      val s3 = TestSubscriber.manualProbe[Int]()
      p3.subscribe(s3)
      val sub3 = s3.expectSubscription()

      sub2.request(2)
      s1.expectNoMsg(200.millis)
      sub3.cancel()

      s1.expectNext(1)
      s2.expectNext(2)
      s2.expectNext(3)
      s1.expectComplete()
      s2.expectComplete()
    }

    "work with 5-way balance" in {

      val (s1, s2, s3, s4, s5) = FlowGraph.closed(Sink.head[Seq[Int]], Sink.head[Seq[Int]], Sink.head[Seq[Int]], Sink.head[Seq[Int]], Sink.head[Seq[Int]])(Tuple5.apply) {
        implicit b ⇒
          (f1, f2, f3, f4, f5) ⇒
            val balance = b.add(Balance[Int](5, waitForAllDownstreams = true))
            Source(0 to 14) ~> balance.in
            balance.out(0).grouped(15) ~> f1.inlet
            balance.out(1).grouped(15) ~> f2.inlet
            balance.out(2).grouped(15) ~> f3.inlet
            balance.out(3).grouped(15) ~> f4.inlet
            balance.out(4).grouped(15) ~> f5.inlet
      }.run()

      Set(s1, s2, s3, s4, s5) flatMap (Await.result(_, 3.seconds)) should be((0 to 14).toSet)
    }

    "fairly balance between three outputs" in {
      val numElementsForSink = 10000
      val outputs = Sink.fold[Int, Int](0)(_ + _)

      val (r1, r2, r3) = FlowGraph.closed(outputs, outputs, outputs)(Tuple3.apply) { implicit b ⇒
        (o1, o2, o3) ⇒
          val balance = b.add(Balance[Int](3, waitForAllDownstreams = true))
          Source.repeat(1).take(numElementsForSink * 3) ~> balance.in
          balance.out(0) ~> o1.inlet
          balance.out(1) ~> o2.inlet
          balance.out(2) ~> o3.inlet
      }.run()

      Await.result(r1, 3.seconds) should be(numElementsForSink +- 2000)
      Await.result(r2, 3.seconds) should be(numElementsForSink +- 2000)
      Await.result(r3, 3.seconds) should be(numElementsForSink +- 2000)
    }

    "produce to second even though first cancels" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      FlowGraph.closed() { implicit b ⇒
        val balance = b.add(Balance[Int](2))
        Source(List(1, 2, 3)) ~> balance.in
        balance.out(0) ~> Sink(c1)
        balance.out(1) ~> Sink(c2)
      }.run()

      val sub1 = c1.expectSubscription()
      sub1.cancel()
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "produce to first even though second cancels" in assertAllStagesStopped {
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()

      FlowGraph.closed() { implicit b ⇒
        val balance = b.add(Balance[Int](2))
        Source(List(1, 2, 3)) ~> balance.in
        balance.out(0) ~> Sink(c1)
        balance.out(1) ~> Sink(c2)
      }.run()

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

      FlowGraph.closed() { implicit b ⇒
        val balance = b.add(Balance[Int](2))
        Source(p1.getPublisher) ~> balance.in
        balance.out(0) ~> Sink(c1)
        balance.out(1) ~> Sink(c2)
      }.run()

      val bsub = p1.expectSubscription()
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()

      sub1.request(1)
      p1.expectRequest(bsub, 16)
      bsub.sendNext(1)
      c1.expectNext(1)

      sub2.request(1)
      bsub.sendNext(2)
      c2.expectNext(2)

      sub1.cancel()
      sub2.cancel()
      bsub.expectCancellation()
    }

  }

}
