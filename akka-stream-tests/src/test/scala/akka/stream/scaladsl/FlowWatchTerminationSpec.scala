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

class FlowWatchTerminationSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)
  implicit val ec = system.dispatcher

  "A WatchTermination" must {

    "complete future when stream is completed" in assertAllStagesStopped {
      val (future, p) = Source(1 to 4).watchTermination()(Keep.right).toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(4).expectNext(1, 2, 3, 4)
      future.pipeTo(testActor)
      expectMsg(())
      p.expectComplete()
    }

    "complete future when stream is cancelled from downstream" in assertAllStagesStopped {
      val (future, p) = Source(1 to 4).watchTermination()(Keep.right).toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(3).expectNext(1, 2, 3).cancel()
      future.pipeTo(testActor)
      expectMsg(())
    }

    "fail future when stream is failed" in assertAllStagesStopped {
      val ex = new RuntimeException("Stream failed.") with NoStackTrace
      val (p, future) = TestSource.probe[Int].watchTermination()(Keep.both).to(Sink.ignore).run()
      p.sendNext(1)
      future.pipeTo(testActor)
      p.sendError(ex)
      expectMsg(Failure(ex))
    }

    "complete the future for an empty stream" in assertAllStagesStopped {
      val (future, p) = Source.empty[Int].watchTermination()(Keep.right).toMat(TestSink.probe[Int])(Keep.both).run()
      p.request(1)
      future.pipeTo(testActor)
      expectMsg(())
    }

  }

}
