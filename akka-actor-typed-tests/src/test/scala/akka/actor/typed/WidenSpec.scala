/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.WordSpecLike

class WidenSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "Widen" should {

    "transform messages from an outer type to an inner type" in {
      val probe = TestProbe[String]()
      val beh = Behaviors.receiveMessage[String] { msg ⇒
        probe.ref ! msg
        Behaviors.same
      }.widen[Int] {
        case n ⇒ n.toString
      }
      val ref = spawn(beh)

      ref ! 42

      probe.expectMessage("42")
    }

    "not build up when the same widen is used many times (initially)" in {
      val probe = TestProbe[String]()
      val transformCount = new AtomicInteger(0)

      // sadly the only "same" we can know is if it is the same PF
      val transform: PartialFunction[String, String] = {
        case s ⇒
          transformCount.incrementAndGet()
          s
      }
      def widen(behavior: Behavior[String]): Behavior[String] =
        behavior.widen(transform)

      val beh =
        widen(
          widen(
            Behaviors.receiveMessage[String] { msg ⇒
              probe.ref ! msg
              Behaviors.same
            }
          )
        )
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
        case s ⇒
          transformCount.incrementAndGet()
          s
      }
      def widen(behavior: Behavior[String]): Behavior[String] =
        behavior.widen(transform)

      def next: Behavior[String] =
        widen(
          Behaviors.receiveMessage[String] { msg ⇒
            probe.ref ! msg
            next
          }
        )

      val ref = spawn(next)

      ref ! "42"
      probe.expectMessage("42")
      transformCount.get should ===(1)

      ref ! "43"
      probe.expectMessage("43")
      transformCount.get should ===(2)

    }
  }

}
