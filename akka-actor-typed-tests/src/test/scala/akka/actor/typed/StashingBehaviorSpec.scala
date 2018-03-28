/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors._
import akka.actor.typed.scaladsl.{ Behaviors, StashBuffer, StashingBehaviors }
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object StashingBehaviorSpec {
  sealed trait Msg
  final case class Slow(d: FiniteDuration)(val replyTo: ActorRef[String]) extends Msg
  final case class Echo(s: String)(val replyTo: ActorRef[String]) extends Msg
}

class StashingBehaviorSpec extends ActorTestKit
  with TypedAkkaSpecWithShutdown with ScalaFutures {
  override def name = "StashingBehavior"
  override def config = ConfigFactory.parseString("akka.loggers = [ akka.testkit.TestEventListener ]")

  import StashingBehaviorSpec._

  implicit def executor: ExecutionContext =
    system.executionContext

  val stashUntil: Behavior[Msg] = setup { _ ⇒
    val buffer = StashBuffer[Msg](4)

    receiveMessage {
      case slow @ Slow(delay) ⇒
        val delayed = akka.pattern.after(delay, system.scheduler)(Future("delayed"))
        StashingBehaviors.until(delayed, buffer, 1.second) { res ⇒
          slow.replyTo ! res.toString
          stashUntil // can't be Behaviors.same
        }

      case other @ Echo(s) ⇒
        other.replyTo ! s
        Behavior.same
    }
  }

  val stashUntilSuccessful: Behavior[Msg] = setup { _ ⇒
    val buffer = StashBuffer[Msg](4)

    receiveMessage {
      case slow @ Slow(delay) ⇒
        val delayed = akka.pattern.after(delay, system.scheduler)(Future("delayed"))
        StashingBehaviors.untilSuccessful(delayed, buffer, 1.second) { res ⇒
          slow.replyTo ! res
          stashUntilSuccessful // can't be Behaviors.same
        }

      case other @ Echo(s) ⇒
        other.replyTo ! s
        Behavior.same
    }
  }

  "StashingBehavior.until pattern" must {
    "stash and then replay" in {
      val ref = spawn(stashUntil)

      val p = TestProbe[String]()
      ref ! Echo("hi")(p.ref)
      p.expectMessage("hi")

      ref ! Slow(200.millis)(p.ref)
      ref ! Echo("1")(p.ref)
      ref ! Echo("2")(p.ref)
      ref ! Echo("3")(p.ref)

      p.expectMessage("Success(delayed)")
      p.expectMessage("1")
      p.expectMessage("2")
      p.expectMessage("3")
    }

    "fail when stash exceeds capacity" in pending
    "fail when timeout triggers" in pending
  }

  "StashingBehavior.untilSuccessful pattern" must {
    "stash and then replay" in {
      val ref = spawn(stashUntilSuccessful)

      val p = TestProbe[String]()
      ref ! Echo("hi")(p.ref)
      p.expectMessage("hi")

      ref ! Slow(200.millis)(p.ref)
      ref ! Echo("1")(p.ref)
      ref ! Echo("2")(p.ref)
      ref ! Echo("3")(p.ref)

      p.expectMessage("delayed")
      p.expectMessage("1")
      p.expectMessage("2")
      p.expectMessage("3")
    }

    "fail when stash exceeds capacity" in pending
    "fail when timeout triggers" in pending
  }
}
