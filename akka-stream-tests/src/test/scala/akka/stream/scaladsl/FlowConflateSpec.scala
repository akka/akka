/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }

class FlowConflateSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  "Conflate" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i).runWith(Sink(subscriber))

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

      Source(publisher).conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i).runWith(Sink(subscriber))

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
      val future = Source(1 to 1000)
        .conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i)
        .map { i ⇒ if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i }
        .fold(0)(_ + _)
      Await.result(future, 10.seconds) should be(500500)
    }

    "backpressure subscriber when upstream is slower" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i).runWith(Sink(subscriber))

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
