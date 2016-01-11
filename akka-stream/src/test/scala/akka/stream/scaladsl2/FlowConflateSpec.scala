/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.MaterializerSettings

class FlowConflateSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  "Conflate" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      FlowFrom(publisher).conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i).publishTo(subscriber)

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      for (i ← 1 to 100) {
        sub.request(1)
        autoPublisher.sendNext(i)
        subscriber.expectNext(i)
      }

      sub.cancel()
    }

    "conflate elements while downstream is silent" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      FlowFrom(publisher).conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i).publishTo(subscriber)

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      for (i ← 1 to 100) {
        autoPublisher.sendNext(i)
      }
      subscriber.expectNoMsg(1.second)
      sub.request(1)
      subscriber.expectNext(5050)
      sub.cancel()
    }

    "work on a variable rate chain" in {
      val foldSink = FoldSink[Int, Int](0)(_ + _)
      val mf = FlowFrom((1 to 1000).iterator)
        .conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i)
        .map { i ⇒ if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i }
        .withSink(foldSink)
        .run()
      val future = foldSink.future(mf)
      Await.result(future, 10.seconds) should be(500500)
    }

    "backpressure subscriber when upstream is slower" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      FlowFrom(publisher).conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i).publishTo(subscriber)

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      sub.request(1)
      autoPublisher.sendNext(1)
      subscriber.expectNext(1)

      sub.request(1)
      subscriber.expectNoMsg(1.second)
      autoPublisher.sendNext(2)
      subscriber.expectNext(2)

      autoPublisher.sendNext(3)
      autoPublisher.sendNext(4)
      sub.request(1)
      subscriber.expectNext(7)

      sub.request(1)
      subscriber.expectNoMsg(1.second)
      sub.cancel()

    }

  }

}
