/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.stream.{ OverflowStrategy, ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit._

class FlowConflateSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  "Conflate" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source(publisher).conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i).to(Sink(subscriber)).run()
      val sub = subscriber.expectSubscription()

      for (i ← 1 to 100) {
        sub.request(1)
        publisher.sendNext(i)
        subscriber.expectNext(i)
      }

      sub.cancel()
    }

    "conflate elements while downstream is silent" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source(publisher).conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i).to(Sink(subscriber)).run()
      val sub = subscriber.expectSubscription()

      for (i ← 1 to 100) {
        publisher.sendNext(i)
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
        .runFold(0)(_ + _)
      Await.result(future, 10.seconds) should be(500500)
    }

    "backpressure subscriber when upstream is slower" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source(publisher).conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i).to(Sink(subscriber)).run()
      val sub = subscriber.expectSubscription()

      sub.request(1)
      publisher.sendNext(1)
      subscriber.expectNext(1)

      sub.request(1)
      subscriber.expectNoMsg(500.millis)
      publisher.sendNext(2)
      subscriber.expectNext(2)

      publisher.sendNext(3)
      publisher.sendNext(4)
      // The request can be in race with the above onNext(4) so the result would be either 3 or 7.
      subscriber.expectNoMsg(500.millis)
      sub.request(1)
      subscriber.expectNext(7)

      sub.request(1)
      subscriber.expectNoMsg(500.millis)
      sub.cancel()

    }

    "work with a buffer and fold" in {
      val future = Source(1 to 50)
        .conflate(seed = i ⇒ i)(aggregate = (sum, i) ⇒ sum + i)
        .buffer(50, OverflowStrategy.backpressure)
        .runFold(0)(_ + _)
      Await.result(future, 3.seconds) should be((1 to 50).sum)
    }

  }

}
