/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorInitializationException
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.EventFilter
import org.scalatest.WordSpecLike

import scala.concurrent.duration._

class WidenSpec extends ScalaTestWithActorTestKit("""
    akka.loggers = [akka.testkit.TestEventListener]
    """) with WordSpecLike {

  implicit val untypedSystem = system.toUntyped

  def intToString(probe: ActorRef[String]): Behavior[Int] = {
    Behaviors
      .receiveMessage[String] { message =>
        probe ! message
        Behaviors.same
      }
      .widen[Int] {
        case n if n != 13 => n.toString
      }
  }

  "Widen" should {

    "transform from an outer type to an inner type" in {
      val probe = TestProbe[String]()
      val ref = spawn(intToString(probe.ref))

      ref ! 42
      probe.expectMessage("42")
    }

    "filter messages" in {
      val probe = TestProbe[String]()
      val ref = spawn(intToString(probe.ref))

      // TestEventListener logs unhandled as warnings, silence that
      EventFilter.warning(occurrences = 1).intercept {
        ref ! 42
        ref ! 13
        ref ! 43
        probe.expectMessage("42")
        probe.expectMessage("43")
      }
    }

    "not build up when the same widen is used many times (initially)" in {
      val probe = TestProbe[String]()
      val transformCount = new AtomicInteger(0)

      // sadly the only "same" we can know is if it is the same PF
      val transform: PartialFunction[String, String] = {
        case s =>
          transformCount.incrementAndGet()
          s
      }
      def widen(behavior: Behavior[String]): Behavior[String] =
        behavior.widen(transform)

      val beh =
        widen(widen(Behaviors.receiveMessage[String] { message =>
          probe.ref ! message
          Behaviors.same
        }))
      val ref = spawn(beh)

      ref ! "42"

      probe.expectMessage("42")
      transformCount.get should ===(1)
    }

    "not build up when the same widen is used many times (recursively)" in {
      val probe = TestProbe[String]()
      val transformCount = new AtomicInteger(0)

      // sadly the only "same" we can know is if it is the same PF
      val transform: PartialFunction[String, String] = {
        case s =>
          transformCount.incrementAndGet()
          s
      }
      def widen(behavior: Behavior[String]): Behavior[String] =
        behavior.widen(transform)

      def next: Behavior[String] =
        widen(Behaviors.receiveMessage[String] { message =>
          probe.ref ! message
          next
        })

      val ref = spawn(next)

      ref ! "42"
      probe.expectMessage("42")
      transformCount.get should ===(1)

      ref ! "43"
      probe.expectMessage("43")
      transformCount.get should ===(2)

    }

    "not allow mixing different widens in the same behavior stack" in {
      val probe = TestProbe[String]()

      def widen(behavior: Behavior[String]): Behavior[String] =
        behavior.widen[String] {
          case s => s.toLowerCase
        }

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        val ref = spawn(widen(widen(Behaviors.receiveMessage[String] { message =>
          Behaviors.same
        })))

        probe.expectTerminated(ref, 3.seconds)
      }

    }
  }

}
