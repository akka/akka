/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.testkit.TestProbe

class FanoutProcessorSpec extends StreamSpec {

  implicit val mat = ActorMaterializer()

  "The FanoutProcessor" must {

    // #25634
    "not leak running actors on failed upstream without subscription" in assertAllStagesStopped {
      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      probe.watch(publisherRef)
      promise.failure(TE("boom"))
      probe.expectTerminated(publisherRef)
    }

    // #25634
    "not leak running actors on failed upstream with one subscription" in assertAllStagesStopped {
      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      Source.fromPublisher(publisher).runWith(Sink.ignore)
      probe.watch(publisherRef)
      val boom = TE("boom")
      promise.failure(boom)
      probe.expectTerminated(publisherRef)
    }

    // #25634
    "not leak running actors on failed upstream with multiple subscriptions" in assertAllStagesStopped {
      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      probe.watch(publisherRef)
      Source.fromPublisher(publisher).runWith(Sink.ignore)
      Source.fromPublisher(publisher).runWith(Sink.ignore)
      val boom = TE("boom")
      promise.failure(boom)
      probe.expectTerminated(publisherRef)
    }

    "not leak running actors on completed upstream no subscriptions" in assertAllStagesStopped {
      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      probe.watch(publisherRef)
      promise.success(None)

      probe.expectTerminated(publisherRef)
    }

    "not leak running actors on completed upstream with one subscription" in assertAllStagesStopped {
      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      val completed = Source.fromPublisher(publisher).runWith(Sink.ignore)
      probe.watch(publisherRef)

      promise.success(None)

      probe.expectTerminated(publisherRef)
      // would throw if not completed
      completed.futureValue
    }

    "not leak running actors on completed upstream with multiple subscriptions" in assertAllStagesStopped {
      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      val completed1 = Source.fromPublisher(publisher).runWith(Sink.ignore)
      val completed2 = Source.fromPublisher(publisher).runWith(Sink.ignore)
      probe.watch(publisherRef)
      promise.success(None)

      probe.expectTerminated(publisherRef)
      // would throw if not completed
      completed1.futureValue
      completed2.futureValue
    }

    "not leak running actors on failed downstream" in assertAllStagesStopped {
      val probe = TestProbe()
      val (_, publisher) = Source.repeat(1).toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      probe.watch(publisherRef)
      Source.fromPublisher(publisher).map(_ => throw TE("boom")).runWith(Sink.ignore)
      probe.expectTerminated(publisherRef)
    }

  }

}
