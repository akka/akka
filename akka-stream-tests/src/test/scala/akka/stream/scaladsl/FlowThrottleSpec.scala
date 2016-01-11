/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ThrottleMode.{ Shaping, Enforcing }
import akka.stream._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NoStackTrace

class FlowThrottleSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(1, 1))

  def genByteString(length: Int) =
    ByteString(new Random().shuffle(0 to 255).take(length).map(_.toByte).toArray)

  "Throttle for single cost elements" must {
    "work for the happy case" in Utils.assertAllStagesStopped {
      Source(1 to 5).throttle(1, 100.millis, 0, Shaping)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "emit single element per tick" in Utils.assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      Source.fromPublisher(upstream).throttle(1, 300.millis, 0, Shaping).runWith(Sink.fromSubscriber(downstream))

      downstream.request(20)
      upstream.sendNext(1)
      downstream.expectNoMsg(150.millis)
      downstream.expectNext(1)

      upstream.sendNext(2)
      downstream.expectNoMsg(150.millis)
      downstream.expectNext(2)

      upstream.sendComplete()
      downstream.expectComplete()
    }

    "not send downstream if upstream does not emit element" in Utils.assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(1, 300.millis, 0, Shaping).runWith(Sink.fromSubscriber(downstream))

      downstream.request(2)
      upstream.sendNext(1)
      downstream.expectNext(1)

      downstream.expectNoMsg(300.millis)
      upstream.sendNext(2)
      downstream.expectNext(2)

      upstream.sendComplete()
    }

    "cancel when downstream cancels" in Utils.assertAllStagesStopped {
      val downstream = TestSubscriber.probe[Int]()
      Source(1 to 10).throttle(1, 300.millis, 0, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.cancel()
    }

    "send elements downstream as soon as time comes" in Utils.assertAllStagesStopped {
      val probe = Source(1 to 10).throttle(2, 500.millis, 0, Shaping).runWith(TestSink.probe[Int])
        .request(5)
      probe.receiveWithin(600.millis) should be(Seq(1, 2))
      probe.expectNoMsg(100.millis)
        .expectNext(3)
        .expectNoMsg(100.millis)
        .expectNext(4)
        .cancel()
    }

    "burst according to its maximum if enough time passed" in Utils.assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(1, 200.millis, 5, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.request(1)
      upstream.sendNext(1)
      downstream.expectNoMsg(100.millis)
      downstream.expectNext(1)
      downstream.request(5)
      downstream.expectNoMsg(1200.millis)
      for (i ← 2 to 6) upstream.sendNext(i)
      downstream.receiveWithin(300.millis, 5) should be(2 to 6)
      downstream.cancel()
    }

    "burst some elements if have enough time" in Utils.assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(1, 200.millis, 5, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.request(1)
      upstream.sendNext(1)
      downstream.expectNoMsg(100.millis)
      downstream.expectNext(1)
      downstream.expectNoMsg(500.millis) //wait to receive 2 in burst afterwards
      downstream.request(5)
      for (i ← 2 to 4) upstream.sendNext(i)
      downstream.receiveWithin(100.millis, 2) should be(Seq(2, 3))
      downstream.cancel()
    }

    "throw exception when exceeding throughtput in enforced mode" in Utils.assertAllStagesStopped {
      an[RateExceededException] shouldBe thrownBy {
        Await.result(
          Source(1 to 5).throttle(1, 200.millis, 5, Enforcing).runWith(Sink.ignore),
          2.seconds)
      }
    }

    "properly combine shape and throttle modes" in Utils.assertAllStagesStopped {
      Source(1 to 5).throttle(1, 100.millis, 5, Shaping)
        .throttle(1, 100.millis, 5, Enforcing)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }
  }

  "Throttle for various cost elements" must {
    "work for happy case" in Utils.assertAllStagesStopped {
      Source(1 to 5).throttle(1, 100.millis, 0, (_) ⇒ 1, Shaping)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "emit elements according to cost" in Utils.assertAllStagesStopped {
      val list = (1 to 4).map(_ * 2).map(genByteString)
      Source(list).throttle(2, 200.millis, 0, _.length, Shaping)
        .runWith(TestSink.probe[ByteString])
        .request(4)
        .expectNext(list(0))
        .expectNoMsg(300.millis)
        .expectNext(list(1))
        .expectNoMsg(500.millis)
        .expectNext(list(2))
        .expectNoMsg(700.millis)
        .expectNext(list(3))
        .expectComplete()
    }

    "not send downstream if upstream does not emit element" in Utils.assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(2, 300.millis, 0, identity, Shaping).runWith(Sink.fromSubscriber(downstream))

      downstream.request(2)
      upstream.sendNext(1)
      downstream.expectNext(1)

      downstream.expectNoMsg(300.millis)
      upstream.sendNext(2)
      downstream.expectNext(2)

      upstream.sendComplete()
    }

    "cancel when downstream cancels" in Utils.assertAllStagesStopped {
      val downstream = TestSubscriber.probe[Int]()
      Source(1 to 10).throttle(2, 200.millis, 0, identity, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.cancel()
    }

    "send elements downstream as soon as time comes" in Utils.assertAllStagesStopped {
      val probe = Source(1 to 10).throttle(4, 500.millis, 0, _ ⇒ 2, Shaping).runWith(TestSink.probe[Int])
        .request(5)
      probe.receiveWithin(600.millis) should be(Seq(1, 2))
      probe.expectNoMsg(100.millis)
        .expectNext(3)
        .expectNoMsg(100.millis)
        .expectNext(4)
        .cancel()
    }

    "burst according to its maximum if enough time passed" in Utils.assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(2, 400.millis, 5, (_) ⇒ 1, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.request(1)
      upstream.sendNext(1)
      downstream.expectNoMsg(100.millis)
      downstream.expectNext(1)
      downstream.request(5)
      downstream.expectNoMsg(1200.millis)
      for (i ← 2 to 6) upstream.sendNext(i)
      downstream.receiveWithin(300.millis, 5) should be(2 to 6)
      downstream.cancel()
    }

    "burst some elements if have enough time" in Utils.assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(2, 400.millis, 5, (e) ⇒ if (e < 4) 1 else 20, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.request(1)
      upstream.sendNext(1)
      downstream.expectNoMsg(100.millis)
      downstream.expectNext(1)
      downstream.expectNoMsg(500.millis) //wait to receive 2 in burst afterwards
      downstream.request(5)
      for (i ← 2 to 4) upstream.sendNext(i)
      downstream.receiveWithin(200.millis, 2) should be(Seq(2, 3))
      downstream.cancel()
    }

    "throw exception when exceeding throughtput in enforced mode" in Utils.assertAllStagesStopped {
      an[RateExceededException] shouldBe thrownBy {
        Await.result(
          Source(1 to 5).throttle(2, 200.millis, 0, identity, Enforcing).runWith(Sink.ignore),
          2.seconds)
      }
    }

    "properly combine shape and throttle modes" in Utils.assertAllStagesStopped {
      Source(1 to 5).throttle(2, 200.millis, 0, identity, Shaping)
        .throttle(1, 100.millis, 5, Enforcing)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "handle rate calculation function exception" in Utils.assertAllStagesStopped {
      val ex = new RuntimeException with NoStackTrace
      Source(1 to 5).throttle(2, 200.millis, 0, (_) ⇒ { throw ex }, Shaping)
        .throttle(1, 100.millis, 5, Enforcing)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectError(ex)
    }
  }
}
