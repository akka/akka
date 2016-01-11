package akka.stream.scaladsl2

import akka.stream.MaterializerSettings
import akka.stream.scaladsl2.FlowGraphImplicits._
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }

import scala.concurrent.Await
import scala.concurrent.duration._

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
        balance ~> SubscriberDrain(c1)
        balance ~> SubscriberDrain(c2)
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

    "work with 5-way balance" in {
      val f1 = FutureDrain[Seq[Int]]
      val f2 = FutureDrain[Seq[Int]]
      val f3 = FutureDrain[Seq[Int]]
      val f4 = FutureDrain[Seq[Int]]
      val f5 = FutureDrain[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance")
        Source(0 to 14) ~> balance
        balance ~> Flow[Int].grouped(15) ~> f1
        balance ~> Flow[Int].grouped(15) ~> f2
        balance ~> Flow[Int].grouped(15) ~> f3
        balance ~> Flow[Int].grouped(15) ~> f4
        balance ~> Flow[Int].grouped(15) ~> f5
      }.run()

      Set(f1, f2, f3, f4, f5) flatMap (sink ⇒ Await.result(g.materializedDrain(sink), 3.seconds)) should be((0 to 14).toSet)
    }

    "fairly balance between three outputs" in {
      val numElementsForSink = 10000
      val f1, f2, f3 = FoldDrain[Int, Int](0)(_ + _)
      val g = FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance")
        Source(Stream.fill(10000 * 3)(1)) ~> balance ~> f1
        balance ~> f2
        balance ~> f3
      }.run()

      Seq(f1, f2, f3) map { sink ⇒
        Await.result(g.materializedDrain(sink), 3.seconds) should be(numElementsForSink +- 1000)
      }
    }

    "produce to second even though first cancels" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val balance = Balance[Int]("balance")
        Source(List(1, 2, 3)) ~> balance
        balance ~> Flow[Int] ~> SubscriberDrain(c1)
        balance ~> Flow[Int] ~> SubscriberDrain(c2)
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
        balance ~> Flow[Int] ~> SubscriberDrain(c1)
        balance ~> Flow[Int] ~> SubscriberDrain(c2)
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
        balance ~> Flow[Int] ~> SubscriberDrain(c1)
        balance ~> Flow[Int] ~> SubscriberDrain(c2)
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
