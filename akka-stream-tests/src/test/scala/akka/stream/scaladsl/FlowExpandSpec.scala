/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings

import akka.stream.testkit._

class FlowExpandSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  "Expand" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.probe[Int]()

      // Simply repeat the last element as an extrapolation step
      Source(publisher).expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i)).to(Sink(subscriber)).run()

      for (i ← 1 to 100) {
        // Order is important here: If the request comes first it will be extrapolated!
        publisher.sendNext(i)
        subscriber.requestNext(i)
      }

      subscriber.cancel()
    }

    "expand elements while upstream is silent" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.probe[Int]()

      // Simply repeat the last element as an extrapolation step
      Source(publisher).expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i)).to(Sink(subscriber)).run()

      publisher.sendNext(42)

      for (i ← 1 to 100) {
        subscriber.requestNext(42)
      }

      publisher.sendNext(-42)
      subscriber.requestNext(-42)

      subscriber.cancel()
    }

    "do not drop last element" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.probe[Int]()

      // Simply repeat the last element as an extrapolation step
      Source(publisher).expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i)).to(Sink(subscriber)).run()

      publisher.sendNext(1)
      subscriber.requestNext(1)

      publisher.sendNext(2)
      publisher.sendComplete()

      subscriber.requestNext(2)
      subscriber.expectComplete()
    }

    "work on a variable rate chain" in {
      val future = Source(1 to 100)
        .map { i ⇒ if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i }
        .expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i))
        .runFold(Set.empty[Int])(_ + _)

      Await.result(future, 10.seconds) should contain theSameElementsAs ((1 to 100).toSet)
    }

    "backpressure publisher when subscriber is slower" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.probe[Int]()

      Source(publisher).expand(seed = i ⇒ i)(extrapolate = i ⇒ (i, i)).to(Sink(subscriber)).run()

      publisher.sendNext(1)
      subscriber.requestNext(1)
      subscriber.requestNext(1)

      var pending = publisher.pending
      // Deplete pending requests coming from input buffer
      while (pending > 0) {
        publisher.unsafeSendNext(2)
        pending -= 1
      }

      // The above sends are absorbed in the input buffer, and will result in two one-sized batch requests
      pending += publisher.expectRequest()
      pending += publisher.expectRequest()
      while (pending > 0) {
        publisher.unsafeSendNext(2)
        pending -= 1
      }

      publisher.expectNoMsg(1.second)

      subscriber.request(2)
      subscriber.expectNext(2)
      subscriber.expectNext(2)

      // Now production is resumed
      publisher.expectRequest()

    }
  }

}
