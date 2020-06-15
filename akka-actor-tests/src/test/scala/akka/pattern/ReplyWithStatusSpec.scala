/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.testkit.AkkaSpec
import akka.testkit.TestException
import akka.testkit.TestProbe
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class ReplyWithStatusSpec extends AkkaSpec with ScalaFutures {

  "ReplyWithStatus" should {
    "pattern match success" in {
      // like in a classic actor receive Any => ...
      (ReplyWithStatus.Success("woho!"): Any) match {
        case ReplyWithStatus.Success(_: Int)                          => fail()
        case ReplyWithStatus.Success(text: String) if text == "woho!" =>
        case _                                                        => fail()
      }
    }

    "pattern match error with text" in {
      ReplyWithStatus.Error("boho!") match {
        case ReplyWithStatus.Error(_) =>
        case _                        => fail()
      }
    }

    "pattern match error with exception" in {
      ReplyWithStatus.Error(TestException("boho!")) match {
        case ReplyWithStatus.Error(_) =>
        case _                        => fail()
      }
    }
  }

  "askWithStatus" should {
    implicit val timeout: Timeout = 3.seconds

    "unwrap success" in {
      val probe = TestProbe()
      val result = probe.ref.askWithStatus("request")
      probe.expectMsg("request")
      probe.lastSender ! ReplyWithStatus.Success("woho")
      result.futureValue should ===("woho")
    }

    "unwrap Error with message" in {
      val probe = TestProbe()
      val result = probe.ref.askWithStatus("request")
      probe.expectMsg("request")
      probe.lastSender ! ReplyWithStatus.Error("boho")
      result.failed.futureValue should ===(ReplyWithStatus.ErrorMessage("boho"))
    }

    "unwrap Error with exception" in {
      val probe = TestProbe()
      val result = probe.ref.askWithStatus("request")
      probe.expectMsg("request")
      probe.lastSender ! ReplyWithStatus.Error(TestException("boho"))
      result.failed.futureValue should ===(TestException("boho"))
    }

  }

}
