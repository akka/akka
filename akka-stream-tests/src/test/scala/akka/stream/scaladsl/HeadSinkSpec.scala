/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import org.reactivestreams.Subscriber

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class HeadSinkSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Flow with Sink.head" must {

    "yield the first value" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f: Future[Int] = Source(p).map(identity).runWith(Sink.head)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(f, 100.millis) should be(42)
      proc.expectCancellation()
    }

    "yield the first value when actively constructing" in {
      val p = TestPublisher.manualProbe[Int]()
      val f = Sink.head[Int]
      val s = Source.subscriber[Int]
      val (subscriber, future) = s.toMat(f)(Keep.both).run()

      p.subscribe(subscriber)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(future, 100.millis) should be(42)
      proc.expectCancellation()
    }

    "yield the first error" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f = Source(p).runWith(Sink.head)
      val proc = p.expectSubscription()
      proc.expectRequest()
      val ex = new RuntimeException("ex")
      proc.sendError(ex)
      Await.ready(f, 100.millis)
      f.value.get should be(Failure(ex))
    }

    "yield NoSuchElementExcption for empty stream" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f = Source(p).runWith(Sink.head)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendComplete()
      Await.ready(f, 100.millis)
      f.value.get match {
        case Failure(e: NoSuchElementException) ⇒ e.getMessage should be("head of empty stream")
        case x                                  ⇒ fail("expected NoSuchElementException, got " + x)
      }
    }

  }
  "A Flow with Sink.headOption" must {

    "yield the first value" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f: Future[Option[Int]] = Source(p).map(identity).runWith(Sink.headOption)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(f, 100.millis) should be(Some(42))
      proc.expectCancellation()
    }

    "yield the first error" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f = Source(p).runWith(Sink.head)
      val proc = p.expectSubscription()
      proc.expectRequest()
      val ex = new RuntimeException("ex")
      proc.sendError(ex)
      Await.ready(f, 100.millis)
      f.value.get should be(Failure(ex))
    }

    "yield None for empty stream" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f = Source(p).runWith(Sink.headOption)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendComplete()
      Await.result(f, 100.millis) should be(None)
    }

  }

}
