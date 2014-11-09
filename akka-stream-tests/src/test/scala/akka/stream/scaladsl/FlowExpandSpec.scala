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

class FlowExpandSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  "Expand" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      // Simply repeat the last element as an extrapolation step
      Source(publisher).expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i)).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      for (i ← 1 to 100) {
        // Order is important here: If the request comes first it will be extrapolated!
        autoPublisher.sendNext(i)
        sub.request(1)
        subscriber.expectNext(i)
      }

      sub.cancel()
    }

    "expand elements while upstream is silent" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      // Simply repeat the last element as an extrapolation step
      Source(publisher).expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i)).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      autoPublisher.sendNext(42)

      for (i ← 1 to 100) {
        sub.request(1)
        subscriber.expectNext(42)
      }

      autoPublisher.sendNext(-42)
      sub.request(1)
      subscriber.expectNext(-42)

      sub.cancel()
    }

    "work on a variable rate chain" in {
      val future = Source(1 to 100)
        .map { i ⇒ if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i }
        .expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i))
        .fold(Set.empty[Int])(_ + _)

      Await.result(future, 10.seconds) should contain theSameElementsAs ((1 to 100).toSet)
    }

    "backpressure publisher when subscriber is slower" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Source(publisher).expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i)).runWith(Sink(subscriber))

      val autoPublisher = new StreamTestKit.AutoPublisher(publisher)
      val sub = subscriber.expectSubscription()

      autoPublisher.sendNext(1)
      sub.request(1)
      subscriber.expectNext(1)
      sub.request(1)
      subscriber.expectNext(1)

      var pending = autoPublisher.pendingRequests
      // Deplete pending requests coming from input buffer
      while (pending > 0) {
        autoPublisher.subscription.sendNext(2)
        pending -= 1
      }

      // The above sends are absorbed in the input buffer, and will result in two one-sized batch requests
      pending += autoPublisher.subscription.expectRequest()
      pending += autoPublisher.subscription.expectRequest()
      while (pending > 0) {
        autoPublisher.subscription.sendNext(2)
        pending -= 1
      }

      publisher.expectNoMsg(1.second)

      sub.request(2)
      subscriber.expectNext(2)
      subscriber.expectNext(2)

      // Now production is resumed
      autoPublisher.subscription.expectRequest()

    }
  }

}
