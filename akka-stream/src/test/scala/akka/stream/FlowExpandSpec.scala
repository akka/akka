/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import akka.stream.scaladsl.Flow
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowExpandSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "Expand" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      // Simply repeat the last element as an extrapolation step
      Flow(publisher).expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i)).produceTo(materializer, subscriber)

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
      Flow(publisher).expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i)).produceTo(materializer, subscriber)

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
      val future = Flow((1 to 100).iterator)
        .map { i ⇒ if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i }
        .expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i))
        .fold(Set.empty[Int])(_ + _)
        .toFuture(materializer)

      Await.result(future, 10.seconds) should be(Set.empty[Int] ++ (1 to 100))
    }

    "backpressure publisher when subscriber is slower" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()

      Flow(publisher).expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i)).produceTo(materializer, subscriber)

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
