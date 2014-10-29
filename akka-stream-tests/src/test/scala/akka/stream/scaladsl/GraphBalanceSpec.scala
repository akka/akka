package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._

import FlowGraphImplicits._
import akka.stream.FlowMaterializer

import akka.stream.MaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }

class GraphBalanceSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A balance" must {

    "balance between subscribers which signal demand" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance")
        Source(List(1, 2, 3)) ~> balance
        balance ~> Sink(c1)
        balance ~> Sink(c2)
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
      val s1 = StreamTestKit.SubscriberProbe[Int]()
      val p2Sink = Sink.publisher[Int]

      val m = FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance", waitForAllDownstreams = true)
        Source(List(1, 2, 3)) ~> balance
        balance ~> Sink(s1)
        balance ~> p2Sink
      }.run()

      val p2 = m.get(p2Sink)

      val sub1 = s1.expectSubscription()
      sub1.request(1)
      s1.expectNoMsg(200.millis)

      val s2 = StreamTestKit.SubscriberProbe[Int]()
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

    "support waiting for demand from all non-cancelled downstream subscriptions" in {
      val s1 = StreamTestKit.SubscriberProbe[Int]()
      val p2Sink = Sink.publisher[Int]
      val p3Sink = Sink.publisher[Int]

      val m = FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance", waitForAllDownstreams = true)
        Source(List(1, 2, 3)) ~> balance
        balance ~> Sink(s1)
        balance ~> p2Sink
        balance ~> p3Sink
      }.run()

      val p2 = m.get(p2Sink)
      val p3 = m.get(p3Sink)

      val sub1 = s1.expectSubscription()
      sub1.request(1)

      val s2 = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(s2)
      val sub2 = s2.expectSubscription()

      val s3 = StreamTestKit.SubscriberProbe[Int]()
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
      val f1 = Sink.future[Seq[Int]]
      val f2 = Sink.future[Seq[Int]]
      val f3 = Sink.future[Seq[Int]]
      val f4 = Sink.future[Seq[Int]]
      val f5 = Sink.future[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance", waitForAllDownstreams = true)
        Source(0 to 14) ~> balance
        balance ~> Flow[Int].grouped(15) ~> f1
        balance ~> Flow[Int].grouped(15) ~> f2
        balance ~> Flow[Int].grouped(15) ~> f3
        balance ~> Flow[Int].grouped(15) ~> f4
        balance ~> Flow[Int].grouped(15) ~> f5
      }.run()

      Set(f1, f2, f3, f4, f5) flatMap (sink ⇒ Await.result(g.get(sink), 3.seconds)) should be((0 to 14).toSet)
    }

    "fairly balance between three outputs" in {
      val numElementsForSink = 10000
      val f1, f2, f3 = Sink.fold[Int, Int](0)(_ + _)
      val g = FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance", waitForAllDownstreams = true)
        Source(Stream.fill(10000 * 3)(1)) ~> balance ~> f1
        balance ~> f2
        balance ~> f3
      }.run()

      Seq(f1, f2, f3) map { sink ⇒
        Await.result(g.get(sink), 3.seconds) should be(numElementsForSink +- 1000)
      }
    }

    "produce to second even though first cancels" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance")
        Source(List(1, 2, 3)) ~> balance
        balance ~> Flow[Int] ~> Sink(c1)
        balance ~> Flow[Int] ~> Sink(c2)
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

    "produce to first even though second cancels" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance")
        Source(List(1, 2, 3)) ~> balance
        balance ~> Flow[Int] ~> Sink(c1)
        balance ~> Flow[Int] ~> Sink(c2)
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

    "cancel upstream when downstreams cancel" in {
      val p1 = StreamTestKit.PublisherProbe[Int]()
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance")
        Source(p1.getPublisher) ~> balance
        balance ~> Flow[Int] ~> Sink(c1)
        balance ~> Flow[Int] ~> Sink(c2)
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
