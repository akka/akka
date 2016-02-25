/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.Done
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import scala.util.control.NoStackTrace
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.TestProbe
import akka.testkit.AkkaSpec

class FlowOnCompleteSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Flow with onComplete" must {

    "invoke callback on normal completion" in assertAllStagesStopped {
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      onCompleteProbe.expectNoMsg(100.millis)
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
      onCompleteProbe.expectNoMsg(100.millis)
    }

    "invoke callback for an empty stream" in assertAllStagesStopped {
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      Source.fromPublisher(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendComplete()
      onCompleteProbe.expectMsg(Success(Done))
      onCompleteProbe.expectNoMsg(100.millis)
    }

    "invoke callback after transform and foreach steps " in assertAllStagesStopped {
      val onCompleteProbe = TestProbe()
      val p = TestPublisher.manualProbe[Int]()
      import system.dispatcher // for the Future.onComplete
      val foreachSink = Sink.foreach[Int] {
        x ⇒ onCompleteProbe.ref ! ("foreach-" + x)
      }
      val future = Source.fromPublisher(p).map { x ⇒
        onCompleteProbe.ref ! ("map-" + x)
        x
      }.runWith(foreachSink)
      future onComplete { onCompleteProbe.ref ! _ }
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      proc.sendComplete()
      onCompleteProbe.expectMsg("map-42")
      onCompleteProbe.expectMsg("foreach-42")
      onCompleteProbe.expectMsg(Success(Done))
    }

  }

}
