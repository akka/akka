/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.AkkaSpec

class HeadSinkSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Flow with Sink.head" must {

    "yield the first value" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f: Future[Int] = Source.fromPublisher(p).map(identity).runWith(Sink.head)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(f, 100.millis) should be(42)
      proc.expectCancellation()
    }

    "yield the first value when actively constructing" in {
      val p = TestPublisher.manualProbe[Int]()
      val f = Sink.head[Int]
      val s = Source.asSubscriber[Int]
      val (subscriber, future) = s.toMat(f)(Keep.both).run()

      p.subscribe(subscriber)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(future, 100.millis) should be(42)
      proc.expectCancellation()
    }

    "yield the first error" in assertAllStagesStopped {
      val ex = new RuntimeException("ex")
      intercept[RuntimeException] {
        Await.result(Source.failed[Int](ex).runWith(Sink.head), 1.second)
      } should be theSameInstanceAs (ex)
    }

    "yield NoSuchElementException for empty stream" in assertAllStagesStopped {
      intercept[NoSuchElementException] {
        Await.result(Source.empty[Int].runWith(Sink.head), 1.second)
      }.getMessage should be("head of empty stream")
    }

  }
  "A Flow with Sink.headOption" must {

    "yield the first value" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val f: Future[Option[Int]] = Source.fromPublisher(p).map(identity).runWith(Sink.headOption)
      val proc = p.expectSubscription()
      proc.expectRequest()
      proc.sendNext(42)
      Await.result(f, 100.millis) should be(Some(42))
      proc.expectCancellation()
    }

    "yield the first error" in assertAllStagesStopped {
      val ex = new RuntimeException("ex")
      intercept[RuntimeException] {
        Await.result(Source.failed[Int](ex).runWith(Sink.head), 1.second)
      } should be theSameInstanceAs (ex)
    }

    "yield None for empty stream" in assertAllStagesStopped {
      Await.result(Source.empty[Int].runWith(Sink.headOption), 1.second) should be(None)
    }

  }

}
