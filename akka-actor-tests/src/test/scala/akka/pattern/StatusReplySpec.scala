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

class StatusReplySpec extends AkkaSpec with ScalaFutures {

  "StatusReply" should {
    "pattern match success" in {
      // like in a classic actor receive Any => ...
      (StatusReply.Success("woho!"): Any) match {
        case StatusReply.Success(_: Int)                          => fail()
        case StatusReply.Success(text: String) if text == "woho!" =>
        case _                                                    => fail()
      }
    }
    "pattern match success (Ack)" in {
      // like in a classic actor receive Any => ...
      (StatusReply.Ack: Any) match {
        case StatusReply.Ack  =>
        case _                => fail()
      }
    }
    "pattern match error with text" in {
      StatusReply.Error("boho!") match {
        case StatusReply.Error(_) =>
        case _                    => fail()
      }
    }

    "pattern match error with exception" in {
      StatusReply.Error(TestException("boho!")) match {
        case StatusReply.Error(_) =>
        case _                    => fail()
      }
    }
  }

  "askWithStatus" should {
    implicit val timeout: Timeout = 3.seconds

    "unwrap success" in {
      val probe = TestProbe()
      val result = probe.ref.askWithStatus("request")
      probe.expectMsg("request")
      probe.lastSender ! StatusReply.Success("woho")
      result.futureValue should ===("woho")
    }

    "unwrap Error with message" in {
      val probe = TestProbe()
      val result = probe.ref.askWithStatus("request")
      probe.expectMsg("request")
      probe.lastSender ! StatusReply.Error("boho")
      result.failed.futureValue should ===(StatusReply.ErrorMessage("boho"))
    }

    "unwrap Error with exception" in {
      val probe = TestProbe()
      val result = probe.ref.askWithStatus("request")
      probe.expectMsg("request")
      probe.lastSender ! StatusReply.Error(TestException("boho"))
      result.failed.futureValue should ===(TestException("boho"))
    }

  }

}
