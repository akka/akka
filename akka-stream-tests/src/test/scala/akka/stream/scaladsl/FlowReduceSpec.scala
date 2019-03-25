/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec

import scala.concurrent.Await
import akka.stream.{ ActorAttributes, ActorMaterializer, Supervision }
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._

import scala.concurrent.duration._

class FlowReduceSpec extends StreamSpec {
  implicit val materializer = ActorMaterializer()

  "A Reduce" must {
    val input = 1 to 100
    val expected = input.sum
    val inputSource = Source(input).filter(_ => true).map(identity)
    val reduceSource = inputSource.reduce[Int](_ + _).filter(_ => true).map(identity)
    val reduceFlow = Flow[Int].filter(_ => true).map(identity).reduce(_ + _).filter(_ => true).map(identity)
    val reduceSink = Sink.reduce[Int](_ + _)

    "work when using Source.runReduce" in assertAllStagesStopped {
      Await.result(inputSource.runReduce(_ + _), 3.seconds) should be(expected)
    }

    "work when using Source.reduce" in assertAllStagesStopped {
      Await.result(reduceSource.runWith(Sink.head), 3.seconds) should be(expected)
    }

    "work when using Sink.reduce" in assertAllStagesStopped {
      Await.result(inputSource.runWith(reduceSink), 3.seconds) should be(expected)
    }

    "work when using Flow.reduce" in assertAllStagesStopped {
      Await.result(inputSource.via(reduceFlow).runWith(Sink.head), 3.seconds) should be(expected)
    }

    "work when using Source.reduce + Flow.reduce + Sink.reduce" in assertAllStagesStopped {
      Await.result(reduceSource.via(reduceFlow).runWith(reduceSink), 3.seconds) should be(expected)
    }

    "propagate an error" in assertAllStagesStopped {
      val error = TE("Boom!")
      val future = inputSource.map(x => if (x > 50) throw error else x).runReduce(Keep.none)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "complete future with failure when reducing function throws and the supervisor strategy decides to stop" in assertAllStagesStopped {
      val error = TE("Boom!")
      val future = inputSource.runReduce[Int]((x, y) => if (x > 50) throw error else x + y)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "resume with the accumulated state when the folding function throws and the supervisor strategy decides to resume" in assertAllStagesStopped {
      val error = TE("Boom!")
      val reduce = Sink.reduce[Int]((x, y) => if (y == 50) throw error else x + y)
      val future =
        inputSource.runWith(reduce.withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider)))
      Await.result(future, 3.seconds) should be(expected - 50)
    }

    "resume and reset the state when the folding function throws when the supervisor strategy decides to restart" in assertAllStagesStopped {
      val error = TE("Boom!")
      val reduce = Sink.reduce[Int]((x, y) => if (y == 50) throw error else x + y)
      val future =
        inputSource.runWith(reduce.withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider)))
      Await.result(future, 3.seconds) should be((51 to 100).sum)
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
