/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.UnhandledMessage
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.{ FishingOutcomes, ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object ForwardSpec {
  sealed trait PingPongCommand
  final case object Ping extends PingPongCommand
  final case object Pong extends PingPongCommand
  final case object UnPingable extends PingPongCommand

  sealed trait BehaviorTag
  final case object PingTag extends BehaviorTag
  final case object PongTag extends BehaviorTag

  sealed trait Event
  final case class ResponseFrom(from: BehaviorTag, cmd: PingPongCommand) extends Event
  final case class ForwardTo(to: BehaviorTag) extends Event

  def ping(monitor: ActorRef[Event]): Behavior[PingPongCommand] =
    Behaviors.receiveMessagePartial[PingPongCommand] {
      case Ping =>
        monitor ! ResponseFrom(PingTag, Ping)
        Behaviors.same
      case msg @ Pong =>
        monitor ! ForwardTo(PongTag)
        Behaviors.forward(pong(monitor), msg)
    }

  def pong(monitor: ActorRef[Event]): Behavior[PingPongCommand] =
    Behaviors.receiveMessage[PingPongCommand] {
      case msg @ Ping =>
        monitor ! ForwardTo(PingTag)
        Behaviors.forward(ping(monitor), msg)
      case Pong =>
        monitor ! ResponseFrom(PongTag, Pong)
        Behaviors.same
      case msg @ UnPingable =>
        monitor ! ForwardTo(PingTag)
        Behaviors.forward(ping(monitor), msg)
    }
}

class ForwardSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  import ForwardSpec._
  implicit val testSettings: TestKitSettings = TestKitSettings(system)

  "Forward behavior" must {
    "forward received message to other behavior and switch to it" in {
      val probe = TestProbe[Event]()
      val behv = ping(probe.ref)
      val ref = spawn(behv)
      ref ! Pong
      probe.expectMessage(ForwardTo(PongTag))
      probe.expectMessage(ResponseFrom(PongTag, Pong))
      ref ! Pong
      probe.expectMessage(ResponseFrom(PongTag, Pong))
      ref ! Ping
      probe.expectMessage(ForwardTo(PingTag))
      probe.expectMessage(ResponseFrom(PingTag, Ping))
    }

    "send unhandled message to deadLetters and recover to original behavior when forwarding" in {
      val deadLetters = TestProbe[UnhandledMessage]("probeDeadLetters")
      system.eventStream ! EventStream.Subscribe[UnhandledMessage](deadLetters.ref)

      val probe = TestProbe[Event]()
      val behv = pong(probe.ref)

      val ref = spawn(behv)

      ref ! UnPingable
      probe.expectMessage(ForwardTo(PingTag))
      deadLetters.fishForMessage(5.seconds) {
        case UnhandledMessage(UnPingable, _, _) => FishingOutcomes.complete
        case _                                  => FishingOutcomes.fail("unexpected message")
      }
      ref ! Ping
      probe.expectMessage(ResponseFrom(PingTag, Ping))
    }
  }

}
