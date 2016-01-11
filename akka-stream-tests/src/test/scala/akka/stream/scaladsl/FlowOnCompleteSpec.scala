/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import scala.util.control.NoStackTrace

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import akka.testkit.TestProbe

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowOnCompleteSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A Flow with onComplete" must {

    "invoke callback on normal completion" in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.PublisherProbe[Int]()
      Source(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      onCompleteProbe.expectNoMsg(100.millis)
      proc.sendComplete()
      onCompleteProbe.expectMsg(Success(()))
    }

    "yield the first error" in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.PublisherProbe[Int]()
      Source(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      val ex = new RuntimeException("ex") with NoStackTrace
      proc.sendError(ex)
      onCompleteProbe.expectMsg(Failure(ex))
      onCompleteProbe.expectNoMsg(100.millis)
    }

    "invoke callback for an empty stream" in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.PublisherProbe[Int]()
      Source(p).to(Sink.onComplete[Int](onCompleteProbe.ref ! _)).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendComplete()
      onCompleteProbe.expectMsg(Success(()))
      onCompleteProbe.expectNoMsg(100.millis)
    }

    "invoke callback after transform and foreach steps " in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.PublisherProbe[Int]()
      import system.dispatcher // for the Future.onComplete
      val foreachSink = Sink.foreach[Int] {
        x ⇒ onCompleteProbe.ref ! ("foreach-" + x)
      }
      val future = Source(p).map { x ⇒
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
      onCompleteProbe.expectMsg(Success(()))
    }

  }

}