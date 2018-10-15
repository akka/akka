/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.TE
import akka.testkit.TestProbe

class FanoutProcessorSpec extends StreamSpec {

  "The FanoutProcessor" must {

    // #25634
    "not leak running actors on failed upstream without subscription" in {
      implicit val mat = ActorMaterializer()

      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      probe.watch(publisherRef)
      promise.failure(TE("boom"))

      probe.expectTerminated(publisherRef)

    }

    // #25634
    "not leak running actors on failed upstream with one subscription" in {
      implicit val mat = ActorMaterializer()

      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      val completed = Source.fromPublisher(publisher).runWith(Sink.ignore)
      probe.watch(publisherRef)
      val boom = TE("boom")
      promise.failure(boom)

      probe.expectTerminated(publisherRef)
      completed.failed.futureValue should ===(boom)

    }

    // #25634
    "not leak running actors on failed upstream with multiple subscriptions" in {
      implicit val mat = ActorMaterializer()

      val probe = TestProbe()
      val (promise, publisher) = Source.maybe[Int].toMat(Sink.asPublisher(true))(Keep.both).run()
      val publisherRef = publisher.asInstanceOf[ActorPublisher[Int]].impl
      val completed1 = Source.fromPublisher(publisher).runWith(Sink.ignore)
      val completed2 = Source.fromPublisher(publisher).runWith(Sink.ignore)
      probe.watch(publisherRef)
      val boom = TE("boom")
      promise.failure(boom)

      probe.expectTerminated(publisherRef)
      completed1.failed.futureValue should ===(boom)
      completed2.failed.futureValue should ===(boom)
    }

  }

}
