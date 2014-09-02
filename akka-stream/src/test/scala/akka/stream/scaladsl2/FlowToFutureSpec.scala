/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.testkit.{ AkkaSpec, ScriptedTest, StreamTestKit }
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import scala.util.Failure
import akka.stream.MaterializerSettings

class FlowToFutureSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A Flow with toFuture" must {

    "yield the first value" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val f = FutureSink[Int]
      val m = FlowFrom(p).withSink(f).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(f.future(m), 100.millis) should be(42)
      proc.expectCancellation()
    }

    "yield the first error" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val f = FutureSink[Int]
      val m = FlowFrom(p).withSink(f).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      val ex = new RuntimeException("ex")
      proc.sendError(ex)
      val future = f.future(m)
      Await.ready(future, 100.millis)
      future.value.get should be(Failure(ex))
    }

    "yield NoSuchElementExcption for empty stream" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val f = FutureSink[Int]
      val m = FlowFrom(p).withSink(f).run()
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendComplete()
      val future = f.future(m)
      Await.ready(future, 100.millis)
      future.value.get match {
        case Failure(e: NoSuchElementException) ⇒ e.getMessage() should be("empty stream")
        case x                                  ⇒ fail("expected NoSuchElementException, got " + x)
      }
    }

  }

}
