/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.stream.{ KillSwitches, ActorMaterializerSettings, ActorMaterializer }

import akka.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

class AdvancedPublisherSinkSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A FunoutWithDrainPublisherSink" must {

    "work in happy case" in assertAllStagesStopped {
      val pub = Source.single(1).runWith(Sink.asPublisher[Int](true, false))
      val sub = TestSubscriber.probe[Int]
      pub.subscribe(sub)
      sub.request(1)
        .expectNext(1)
        .expectComplete()
    }

    "drain buffer when no subscribers attached" in assertAllStagesStopped {
      val sub1 = TestSubscriber.probe[Int]
      val sub2 = TestSubscriber.probe[Int]
      val src = TestPublisher.probe[Int]()
      val pub = Source.fromPublisher(src).runWith(Sink.asPublisher[Int](true, false))

      pub.subscribe(sub1)
      sub1.request(1)
      sub1.expectNoMsg(200.millis)
      src.sendNext(1)
      sub1.expectNext(1).cancel()
      src.sendNext(2)
      src.sendNext(3)

      src.expectNoMsg(200.millis) // wait while two messages reach publisher

      pub.subscribe(sub2)
      sub2.request(1)
      sub2.expectNoMsg(200.millis)
      src.sendNext(4)
      sub2.expectNext(4)
      src.sendComplete()

      sub2.expectComplete()
    }

    "drain buffer first before cancel all publishers" in assertAllStagesStopped {
      val src = TestPublisher.probe[Int]()
      val pub = Source.fromPublisher(src).runWith(Sink.asPublisher[Int](true, false))
      val sub1 = TestSubscriber.probe[Int]
      val sub2 = TestSubscriber.probe[Int]

      pub.subscribe(sub1)
      pub.subscribe(sub2)
      sub1.request(2)
      src.sendNext(1)
      src.sendNext(2)
      sub1.expectNext(1, 2)

      src.sendComplete()
      sub1.expectComplete()

      sub2.request(2)
      sub2.expectNext(1, 2)
      sub2.expectComplete()
    }
  }

  "A DrainWithSingleSubscriptionPublisherSink" must {

    "work in happy case" in assertAllStagesStopped {
      val pub = Source.single(1).runWith(Sink.asPublisher[Int](false, false))
      val sub = TestSubscriber.probe[Int]
      pub.subscribe(sub)
      sub.request(1)
        .expectNext(1)
        .expectComplete()
    }

    "drain buffer when no subscribers attached" in assertAllStagesStopped {
      val sub1 = TestSubscriber.probe[Int]
      val sub2 = TestSubscriber.probe[Int]
      val src = TestPublisher.probe[Int]()
      val pub = Source.fromPublisher(src).runWith(Sink.asPublisher[Int](false, false))

      pub.subscribe(sub1)
      sub1.request(1)
      sub1.expectNoMsg(200.millis)
      src.sendNext(1)
      sub1.expectNext(1).cancel()
      src.sendNext(2)
      src.sendNext(3)

      src.expectNoMsg(200.millis) // wait while two messages reach publisher

      pub.subscribe(sub2)
      sub2.request(1)
      sub2.expectNoMsg(200.millis)
      src.sendNext(4)
      sub2.expectNext(4)
      src.sendComplete()

      sub2.expectComplete()
    }

    "fail when more then one subscription" in assertAllStagesStopped {
      val src = TestPublisher.probe[Int]()
      val sub1 = TestSubscriber.probe[Int]
      val sub2 = TestSubscriber.probe[Int]
      val pub = Source.fromPublisher(src).runWith(Sink.asPublisher[Int](false, false))
      pub.subscribe(sub1)
      pub.subscribe(sub2)
      sub2.expectSubscriptionAndError()
      src.sendComplete()
    }

    "drain buffer first before cancel publisher" in assertAllStagesStopped {
      val settings = ActorMaterializerSettings(system)
        .withInputBuffer(initialSize = 1, maxSize = 1)
      val mat = ActorMaterializer(settings)

      val src = TestPublisher.probe[Int]()
      val pub = Source.fromPublisher(src).runWith(Sink.asPublisher[Int](false, false))(mat)
      val sub1 = TestSubscriber.probe[Int]

      pub.subscribe(sub1)
      val sub = src.expectSubscription()
      sub1.request(2)

      sub.expectRequest(1)
      sub.sendNext(1)
      sub.expectRequest(1)
      sub.sendNext(2)
      sub1.expectNext(1)

      sub.sendComplete()
      sub1.expectNext(2)
      sub1.expectComplete()
    }

  }

}
