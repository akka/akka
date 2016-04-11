/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.util.control.NoStackTrace

import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

class FlowReduceSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "A Reduce" must {
    val input = 1 to 100
    val expected = input.sum
    val inputSource = Source(input).filter(_ ⇒ true).map(identity)
    val reduceSource = inputSource.reduce[Int](_ + _).filter(_ ⇒ true).map(identity)
    val reduceFlow = Flow[Int].filter(_ ⇒ true).map(identity).reduce(_ + _).filter(_ ⇒ true).map(identity)
    val reduceSink = Sink.reduce[Int](_ + _)

    "work when using Source.runReduce" in assertAllStagesStopped {
      Await.result(inputSource.runReduce(_ + _), 3.seconds) should be(expected)
    }

    "work when using Source.reduce" in assertAllStagesStopped {
      Await.result(reduceSource runWith Sink.head, 3.seconds) should be(expected)
    }

    "work when using Sink.reduce" in assertAllStagesStopped {
      Await.result(inputSource runWith reduceSink, 3.seconds) should be(expected)
    }

    "work when using Flow.reduce" in assertAllStagesStopped {
      Await.result(inputSource via reduceFlow runWith Sink.head, 3.seconds) should be(expected)
    }

    "work when using Source.reduce + Flow.reduce + Sink.reduce" in assertAllStagesStopped {
      Await.result(reduceSource via reduceFlow runWith reduceSink, 3.seconds) should be(expected)
    }

    "propagate an error" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = inputSource.map(x ⇒ if (x > 50) throw error else x).runReduce(Keep.none)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "complete future with failure when reducing function throws" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = inputSource.runReduce[Int]((x, y) ⇒ if (x > 50) throw error else x + y)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "fail on empty stream using Source.runReduce" in assertAllStagesStopped {
      val result = Source.empty[Int].runReduce(_ + _)
      val ex = intercept[NoSuchElementException] { Await.result(result, 3.seconds) }
      ex.getMessage should include("empty stream")
    }

    "fail on empty stream using Flow.reduce" in assertAllStagesStopped {
      val result = Source.empty[Int].via(reduceFlow).runWith(Sink.fold(0)(_ + _))
      val ex = intercept[NoSuchElementException] { Await.result(result, 3.seconds) }
      ex.getMessage should include("empty stream")
    }

    "fail on empty stream using Sink.reduce" in assertAllStagesStopped {
      val result = Source.empty[Int].runWith(reduceSink)
      val ex = intercept[NoSuchElementException] { Await.result(result, 3.seconds) }
      ex.getMessage should include("empty stream")
    }

  }

}
