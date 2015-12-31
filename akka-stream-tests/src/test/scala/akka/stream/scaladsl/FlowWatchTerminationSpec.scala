/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.stream._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }

import scala.util.control.NoStackTrace
import scala.concurrent.duration._

class FlowWatchTerminationSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)
  implicit val ec = system.dispatcher

  "A WatchTermination" must {

    "complete future when stream is completed" in assertAllStagesStopped {
      val (future, p) = Source(1 to 4).watchTermination().toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(4).expectNext(1, 2, 3, 4)
      future.pipeTo(testActor)
      expectMsg(())
      p.expectComplete()
    }

    "complete future when stream is cancelled from downstream" in assertAllStagesStopped {
      val (future, p) = Source(1 to 4).watchTermination().toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(3).expectNext(1, 2, 3).cancel()
      future.pipeTo(testActor)
      expectMsg(())
    }

    "fail future when stream is failed" in assertAllStagesStopped {
      val ex = new RuntimeException("Stream failed.") with NoStackTrace
      val (p, future) = TestSource.probe[Int].watchTerminationMat()(Keep.both).to(Sink.ignore).run()
      p.sendNext(1)
      future.pipeTo(testActor)
      p.sendError(ex)
      expectMsg(Failure(ex))
    }

    "complete the future for an empty stream" in assertAllStagesStopped {
      val (future, p) = Source.empty[Int].watchTermination().toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(1)
      future.pipeTo(testActor)
      expectMsg(())
    }

    "complete future for graph" in assertAllStagesStopped {
      val ((souceProbe, future), sinkProbe) = TestSource.probe[Int].watchTerminationMat()(Keep.both).concat(Source(2 to 5)).toMat(TestSink.probe[Int])(Keep.both).run()
      future.pipeTo(testActor)
      sinkProbe.request(5)
      souceProbe.sendNext(1)
      sinkProbe.expectNext(1)
      expectNoMsg(300.millis)

      souceProbe.sendComplete()
      expectMsg(())

      sinkProbe.expectNextN(2 to 5)
        .expectComplete()
    }

  }

}
