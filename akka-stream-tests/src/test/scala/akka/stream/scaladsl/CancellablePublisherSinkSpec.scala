/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.stream.{ ActorMaterializerSettings, ActorMaterializer }

import akka.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

class CancellablePublisherSinkSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A CancellablePublisherSink" must {

    "work in happy case" in assertAllStagesStopped {
      val pub = Source.single(1).runWith(Sink.asPublisher[Int]())
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
      val pub = Source.fromPublisher(src).runWith(Sink.asPublisher[Int]())

      pub.subscribe(sub1)
      sub1.request(1)
      sub1.expectNoMsg(200.millis)
      src.sendNext(1)
      sub1.expectNext(1).cancel()
      src.sendNext(2)
      src.sendNext(3)

      sub2.expectNoMsg(200.millis) // wait while two messages reach publisher

      pub.subscribe(sub2)
      sub2.request(1)
      sub2.expectNoMsg(200.millis)
      src.sendNext(4)
      sub2.expectNext(4)
      src.sendComplete()

      sub2.expectComplete()
    }

    "be cancellable from publisher" in assertAllStagesStopped {
      val pub = Source(1 to 3).runWith(Sink.asPublisher[Int]())
      val sub = TestSubscriber.probe[Int]
      pub.subscribe(sub)
      sub.request(1)
        .expectNext(1)

      pub.cancel()
      sub.expectComplete()
    }

    "drain buffer first before cancel all publishers" in assertAllStagesStopped {
      val pub = Source(1 to 3).runWith(Sink.asPublisher[Int]())
      val sub1 = TestSubscriber.probe[Int]
      val sub2 = TestSubscriber.probe[Int]

      pub.subscribe(sub1)
      pub.subscribe(sub2)
      sub1.request(2)
        .expectNext(1, 2)

      pub.cancel()
      sub1.expectComplete()

      sub2.request(2)
        .expectNext(1, 2)
      sub2.expectComplete()
    }

  }

}
