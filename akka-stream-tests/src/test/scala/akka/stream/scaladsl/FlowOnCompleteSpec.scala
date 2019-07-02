/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.Done
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import scala.util.control.NoStackTrace
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.testkit.TestProbe

class FlowOnCompleteSpec extends StreamSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Flow with onComplete" must {

    "invoke callback on normal completion" in assertAllStagesStopped {
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      onCompleteProbe.expectNoMessage(100.millis)
      proc.sendComplete()
      onCompleteProbe.expectMsg(Success(Done))
    }

    "yield the first error" in assertAllStagesStopped {
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      val ex = new RuntimeException("ex") with NoStackTrace
      proc.sendError(ex)
      onCompleteProbe.expectMsg(Failure(ex))
      onCompleteProbe.expectNoMessage(100.millis)
    }

    "invoke callback for an empty stream" in assertAllStagesStopped {
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendComplete()
      onCompleteProbe.expectMsg(Success(Done))
      onCompleteProbe.expectNoMessage(100.millis)
    }

    "invoke callback after transform and foreach steps " in assertAllStagesStopped {
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      import system.dispatcher // for the Future.onComplete
      val foreachSink = Sink.foreach[Int] { x =>
        onCompleteProbe.ref ! ("foreach-" + x)
      }
      val future = Source
        .fromPublisher(p)
        .map { x =>
          onCompleteProbe.ref ! ("map-" + x)
          x
        }
        .runWith(foreachSink)
      future.onComplete { onCompleteProbe.ref ! _ }
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      proc.sendComplete()
      onCompleteProbe.expectMsg("map-42")
      onCompleteProbe.expectMsg("foreach-42")
      onCompleteProbe.expectMsg(Success(Done))
    }

    "yield error on abrupt termination" in {
      val mat = ActorMaterializer()
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()(mat)
      val proc = p.expectSubscription()
      proc.expectRequest()
      mat.shutdown()

      onCompleteProbe.expectMsgType[Failure[_]]
    }

  }

}
