/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.util.control.NoStackTrace

import akka.stream.{ OverflowStrategy, ActorFlowMaterializer }
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

class FlowFoldSpec extends AkkaSpec {
  implicit val mat = ActorFlowMaterializer()

  "A Fold" must {

    "fold" in assertAllStagesStopped {
      val input = 1 to 100
      val future = Source(input).runFold(0)(_ + _)
      val expected = input.fold(0)(_ + _)
      Await.result(future, 3.seconds) should be(expected)
    }

    "propagate an error" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = Source[Unit](() ⇒ throw error).runFold(())((_, _) ⇒ ())
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "complete future with failure when function throws" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = Source.single(1).runFold(0)((_, _) ⇒ throw error)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

  }

}
