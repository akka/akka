/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed

import scala.concurrent.Await
import scala.util.control.NoStackTrace

import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

class FlowFoldSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "A Fold" must {
    val input = 1 to 100
    val expected = input.sum
    val inputSource = Source(input).filter(_ ⇒ true).map(identity)
    val foldSource = inputSource.fold[Int](0)(_ + _).filter(_ ⇒ true).map(identity)
    val foldFlow = Flow[Int].filter(_ ⇒ true).map(identity).fold(0)(_ + _).filter(_ ⇒ true).map(identity)
    val foldSink = Sink.fold[Int, Int](0)(_ + _)

    "work when using Source.runFold" in assertAllStagesStopped {
      Await.result(inputSource.runFold(0)(_ + _), 3.seconds) should be(expected)
    }

    "work when using Source.fold" in assertAllStagesStopped {
      Await.result(foldSource runWith Sink.head, 3.seconds) should be(expected)
    }

    "work when using Sink.fold" in assertAllStagesStopped {
      Await.result(inputSource runWith foldSink, 3.seconds) should be(expected)
    }

    "work when using Flow.fold" in assertAllStagesStopped {
      Await.result(inputSource via foldFlow runWith Sink.head, 3.seconds) should be(expected)
    }

    "work when using Source.fold + Flow.fold + Sink.fold" in assertAllStagesStopped {
      Await.result(foldSource via foldFlow runWith foldSink, 3.seconds) should be(expected)
    }

    "propagate an error" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = inputSource.map(x ⇒ if (x > 50) throw error else x).runFold[NotUsed](NotUsed)(Keep.none)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "complete future with failure when folding function throws" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = inputSource.runFold(0)((x, y) ⇒ if (x > 50) throw error else x + y)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

  }

}
