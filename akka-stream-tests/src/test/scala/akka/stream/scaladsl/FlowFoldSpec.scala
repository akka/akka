/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.util.control.NoStackTrace

import akka.stream.{ OverflowStrategy, ActorFlowMaterializer }
import akka.stream.testkit.AkkaSpec
import akka.testkit.DefaultTimeout

class FlowFoldSpec extends AkkaSpec with DefaultTimeout {
  implicit val mat = ActorFlowMaterializer()

  "A Fold" must {

    "fold" in {
      val input = 1 to 100
      val future = Source(input).runFold(0)(_ + _)
      val expected = input.fold(0)(_ + _)
      Await.result(future, timeout.duration) should be(expected)
    }

    "propagate an error" in {
      val error = new Exception with NoStackTrace
      val future = Source[Unit](() ⇒ throw error).runFold(())((_, _) ⇒ ())
      the[Exception] thrownBy Await.result(future, timeout.duration) should be(error)
    }

  }

}