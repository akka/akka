package akka.stream.scaladsl2

import akka.stream.{ OverflowStrategy, MaterializerSettings }
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import scala.concurrent.Await
import scala.concurrent.duration._
import FlowGraphImplicits._

class GraphBroadcastSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A broadcast" must {

    "broadcast to other subscriber" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        Source(List(1, 2, 3)) ~> bcast
        bcast ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink(c1)
        bcast ~> Flow[Int].buffer(16, OverflowStrategy.backpressure) ~> Sink(c2)
      }.run()

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

    "work with n-way broadcast" in {
      val f1 = Sink.future[Seq[Int]]
      val f2 = Sink.future[Seq[Int]]
      val f3 = Sink.future[Seq[Int]]
      val f4 = Sink.future[Seq[Int]]
      val f5 = Sink.future[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        Source(List(1, 2, 3)) ~> bcast
        bcast ~> Flow[Int].grouped(5) ~> f1
        bcast ~> Flow[Int].grouped(5) ~> f2
        bcast ~> Flow[Int].grouped(5) ~> f3
        bcast ~> Flow[Int].grouped(5) ~> f4
        bcast ~> Flow[Int].grouped(5) ~> f5
      }.run()

      Await.result(g.get(f1), 3.seconds) should be(List(1, 2, 3))
      Await.result(g.get(f2), 3.seconds) should be(List(1, 2, 3))
      Await.result(g.get(f3), 3.seconds) should be(List(1, 2, 3))
      Await.result(g.get(f4), 3.seconds) should be(List(1, 2, 3))
      Await.result(g.get(f5), 3.seconds) should be(List(1, 2, 3))
    }

    "produce to other even though downstream cancels" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        Source(List(1, 2, 3)) ~> bcast
        bcast ~> Flow[Int] ~> Sink(c1)
        bcast ~> Flow[Int] ~> Sink(c2)
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

    "produce to downstream even though other cancels" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        Source(List(1, 2, 3)) ~> bcast
        bcast ~> Flow[Int] ~> Sink(c1)
        bcast ~> Flow[Int] ~> Sink(c2)
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
        val bcast = Broadcast[Int]("broadcast")
        Source(p1.getPublisher) ~> bcast
        bcast ~> Flow[Int] ~> Sink(c1)
        bcast ~> Flow[Int] ~> Sink(c2)
      }.run()

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

  }

}
