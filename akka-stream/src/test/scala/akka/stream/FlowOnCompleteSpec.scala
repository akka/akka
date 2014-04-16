/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.testkit.StreamTestKit
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure
import akka.stream.scaladsl.Flow
import akka.testkit.TestProbe
import scala.util.Try
import scala.util.Success

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowOnCompleteSpec extends AkkaSpec with ScriptedTest {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16))

  "A Flow with onComplete" must {

    "invoke callback on normal completion" in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.producerProbe[Int]
      Flow(p).onComplete(materializer) { onCompleteProbe.ref ! _ }
      val proc = p.expectSubscription
      proc.expectRequestMore()
      proc.sendNext(42)
      onCompleteProbe.expectNoMsg(100.millis)
      proc.sendComplete()
      onCompleteProbe.expectMsg(Success(()))
    }

    "yield the first error" in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.producerProbe[Int]
      Flow(p).onComplete(materializer) { onCompleteProbe.ref ! _ }
      val proc = p.expectSubscription
      proc.expectRequestMore()
      val ex = new RuntimeException("ex")
      proc.sendError(ex)
      onCompleteProbe.expectMsg(Failure(ex))
      onCompleteProbe.expectNoMsg(100.millis)
    }

    "invoke callback for an empty stream" in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.producerProbe[Int]
      Flow(p).onComplete(materializer) { onCompleteProbe.ref ! _ }
      val proc = p.expectSubscription
      proc.expectRequestMore()
      proc.sendComplete()
      onCompleteProbe.expectMsg(Success(()))
      onCompleteProbe.expectNoMsg(100.millis)
    }

    "invoke callback after transform and foreach steps " in {
      val onCompleteProbe = TestProbe()
      val p = StreamTestKit.producerProbe[Int]
      Flow(p).map { x ⇒
        onCompleteProbe.ref ! ("map-" + x)
        x
      }.foreach {
        x ⇒ onCompleteProbe.ref ! ("foreach-" + x)
      }.onComplete(materializer) { onCompleteProbe.ref ! _ }
      val proc = p.expectSubscription
      proc.expectRequestMore()
      proc.sendNext(42)
      proc.sendComplete()
      onCompleteProbe.expectMsg("map-42")
      onCompleteProbe.expectMsg("foreach-42")
      onCompleteProbe.expectMsg(Success(()))
    }

  }

}