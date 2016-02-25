/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.AkkaSpec

class LastSinkSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)

  "A Flow with Sink.last" must {

    "yield the last value" in assertAllStagesStopped {
      Await.result(Source(1 to 42).map(identity).runWith(Sink.last), 1.second) should be(42)
    }

    "yield the first error" in assertAllStagesStopped {
      val ex = new RuntimeException("ex")
      intercept[RuntimeException] {
        Await.result(Source.failed[Int](ex).runWith(Sink.last), 1.second)
      } should be theSameInstanceAs (ex)
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
      intercept[RuntimeException] {
        Await.result(Source.failed[Int](ex).runWith(Sink.lastOption), 1.second)
      } should be theSameInstanceAs (ex)
    }

    "yield None for empty stream" in assertAllStagesStopped {
      Await.result(Source.empty[Int].runWith(Sink.lastOption), 1.second) should be(None)
    }

  }

}
