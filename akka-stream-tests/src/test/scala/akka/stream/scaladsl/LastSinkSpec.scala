/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.StreamTestKit._

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class LastSinkSpec extends StreamSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer: ActorMaterializer = ActorMaterializer(settings)
  implicit val ec = system.dispatcher

  "A Flow with Sink.last" must {

    "yield the last value" in {
      //#last-operator-example
      val source = Source(1 to 10)
      val result: Future[Int] = source.runWith(Sink.last)
      result.map(println)
      // 10
      //#last-operator-example
      result.futureValue shouldEqual 10
    }

    "yield the first error" in assertAllStagesStopped {
      val ex = new RuntimeException("ex")
      (intercept[RuntimeException] {
        Await.result(Source.failed[Int](ex).runWith(Sink.last), 1.second)
      } should be).theSameInstanceAs(ex)
    }

    "yield NoSuchElementException for empty stream" in assertAllStagesStopped {
      intercept[NoSuchElementException] {
        Await.result(Source.empty[Int].runWith(Sink.last), 1.second)
      }.getMessage should be("last of empty stream")
    }

  }
  "A Flow with Sink.lastOption" must {

    "yield the last value" in assertAllStagesStopped {
      Await.result(Source(1 to 42).map(identity).runWith(Sink.lastOption), 1.second) should be(Some(42))
    }

    "yield the first error" in assertAllStagesStopped {
      val ex = new RuntimeException("ex")
      (intercept[RuntimeException] {
        Await.result(Source.failed[Int](ex).runWith(Sink.lastOption), 1.second)
      } should be).theSameInstanceAs(ex)
    }

    "yield None for empty stream" in {
      //#lastOption-operator-example
      val source = Source.empty[Int]
      val result: Future[Option[Int]] = source.runWith(Sink.lastOption)
      result.map(println)
      // None
      //#lastOption-operator-example
      result.futureValue shouldEqual None
    }

  }

}
