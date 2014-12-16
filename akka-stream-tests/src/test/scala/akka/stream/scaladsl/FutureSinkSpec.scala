/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.testkit.ScriptedTest

class HeadSinkSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A Flow with Sink.head" must {

    "yield the first value" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val f: Future[Int] = Source(p).map(identity).runWith(Sink.head)
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(f, 100.millis) should be(42)
      proc.expectCancellation()
    }

    "yield the first value when actively constructing" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val f = Sink.head[Int]
      val s = Source.subscriber[Int]
      val m = s.to(f).run()
      p.subscribe(m.get(s))
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(m.get(f), 100.millis) should be(42)
      proc.expectCancellation()
    }

    "yield the first error" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val f = Source(p).runWith(Sink.head)
      val proc = p.expectSubscription
      proc.expectRequest()
      val ex = new RuntimeException("ex")
      proc.sendError(ex)
      Await.ready(f, 100.millis)
      f.value.get should be(Failure(ex))
    }

    "yield NoSuchElementExcption for empty stream" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val f = Source(p).runWith(Sink.head)
      val proc = p.expectSubscription
      proc.expectRequest()
      proc.sendComplete()
      Await.ready(f, 100.millis)
      f.value.get match {
        case Failure(e: NoSuchElementException) ⇒ e.getMessage() should be("empty stream")
        case x                                  ⇒ fail("expected NoSuchElementException, got " + x)
      }
    }

  }

}
