/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.Done
import akka.pattern.pipe
import akka.stream._
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }

import scala.util.control.NoStackTrace
import scala.concurrent.duration._

class FlowWatchTerminationSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)

  "A WatchTermination" must {

    "complete future when stream is completed" in assertAllStagesStopped {
      val (future, p) = Source(1 to 4).watchTermination()(Keep.right).toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(4).expectNext(1, 2, 3, 4)
      future.futureValue should ===(Done)
      p.expectComplete()
    }

    "complete future when stream is cancelled from downstream" in assertAllStagesStopped {
      val (future, p) = Source(1 to 4).watchTermination()(Keep.right).toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(3).expectNext(1, 2, 3).cancel()
      future.futureValue should ===(Done)
    }

    "fail future when stream is failed" in assertAllStagesStopped {
      val ex = new RuntimeException("Stream failed.") with NoStackTrace
      val (p, future) = TestSource.probe[Int].watchTermination()(Keep.both).to(Sink.ignore).run()
      p.sendNext(1)
      p.sendError(ex)
      whenReady(future.failed) { _ shouldBe (ex) }
    }

    "complete the future for an empty stream" in assertAllStagesStopped {
      val (future, p) = Source.empty[Int].watchTermination()(Keep.right).toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(1)
      future.futureValue should ===(Done)
    }

    "complete future for graph" in assertAllStagesStopped {
      implicit val ec = system.dispatcher

      val ((sourceProbe, future), sinkProbe) = TestSource.probe[Int].watchTermination()(Keep.both).concat(Source(2 to 5)).toMat(TestSink.probe[Int])(Keep.both).run()
      future.pipeTo(testActor)
      sinkProbe.request(5)
      sourceProbe.sendNext(1)
      sinkProbe.expectNext(1)
      expectNoMessage(300.millis)

      sourceProbe.sendComplete()
      expectMsg(Done)

      sinkProbe.expectNextN(2 to 5)
        .expectComplete()
    }

    "fail future when stream abruptly terminated" in {
      val mat = ActorMaterializer()

      val (p, future) = TestSource.probe[Int].watchTermination()(Keep.both).to(Sink.ignore).run()(mat)
      mat.shutdown()

      future.failed.futureValue shouldBe an[AbruptTerminationException]
    }

  }

}
