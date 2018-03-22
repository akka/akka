/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ AbruptStageTerminationException, ActorMaterializer }
import akka.stream.testkit.{ StreamSpec, TestSubscriber, Utils }
import akka.testkit.DefaultTimeout

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class MaybeSourceSpec extends StreamSpec with DefaultTimeout {

  implicit val materializer = ActorMaterializer()

  "The Maybe Source" must {

    "complete materialized future with None when stream cancels" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
      val pubSink = Sink.asPublisher[Int](false)

      val (f, neverPub) = neverSource.toMat(pubSink)(Keep.both).run()

      val c = TestSubscriber.manualProbe[Int]()
      neverPub.subscribe(c)
      val subs = c.expectSubscription()

      subs.request(1000)
      c.expectNoMsg(300.millis)

      subs.cancel()
      f.future.futureValue shouldEqual None
    }

    "allow external triggering of empty completion" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int].filter(_ ⇒ false)
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.trySuccess(None) shouldEqual true

      counterFuture.futureValue shouldEqual 0
    }

    "allow external triggering of empty completion when there was no demand" in Utils.assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      val promise = Source.maybe[Int].to(Sink.fromSubscriber(probe)).run()

      // external cancellation
      probe.ensureSubscription()
      promise.trySuccess(None) shouldEqual true
      probe.expectComplete()
    }

    "allow external triggering of non-empty completion" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
      val counterSink = Sink.head[Int]

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.trySuccess(Some(6)) shouldEqual true

      counterFuture.futureValue shouldEqual 6
    }

    "allow external triggering of onError" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.tryFailure(new Exception("Boom") with NoStackTrace) shouldEqual true

      counterFuture.failed.futureValue.getMessage should include("Boom")
    }

    "complete materialized future when materializer is shutdown" in Utils.assertAllStagesStopped {
      val mat = ActorMaterializer()
      val neverSource = Source.maybe[Int]
      val pubSink = Sink.asPublisher[Int](false)

      val (f, neverPub) = neverSource.toMat(pubSink)(Keep.both).run()(mat)

      val c = TestSubscriber.manualProbe[Int]()
      neverPub.subscribe(c)
      val subs = c.expectSubscription()

      mat.shutdown()
      f.future.failed.futureValue shouldBe an[AbruptStageTerminationException]
    }

  }
}
